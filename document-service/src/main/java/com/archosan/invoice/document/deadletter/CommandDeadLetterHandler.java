package com.archosan.invoice.document.deadletter;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.document.persistence.DeadLetterRepository;
import com.archosan.invoice.document.persistence.DocumentRepository;
import com.archosan.invoice.document.statemachine.StatusTransitions;
import com.archosan.invoice.document.statemachine.TransitionOutcome;
import com.archosan.invoice.document.statemachine.Trigger;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.consumer.DeadLetter;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.archosan.invoice.messaging.message.PostToPortal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

import static com.archosan.invoice.document.DocumentStatus.NEEDS_REVIEW;
import static com.archosan.invoice.document.DocumentStatus.PENDING_APPROVAL;
import static com.archosan.invoice.document.DocumentStatus.QUEUED_FOR_RPA;
import static com.archosan.invoice.document.DocumentStatus.RECEIVED;
import static com.archosan.invoice.document.DocumentStatus.RPA_FAILED;
import static com.archosan.invoice.document.DocumentStatus.VALIDATED;

/**
 * Komut DLQ'ları (FR-D10): mesaj {@code dead_letters}'a park edilir (ADR-18) ve kaydın durumu tabloya göre ilerler,
 * aynı transaction'da.
 *
 * <ul>
 *   <li>{@code ExtractInvoice} → {@code RECEIVED → NEEDS_REVIEW}</li>
 *   <li>{@code CheckCompliance} → {@code VALIDATED → PENDING_APPROVAL}</li>
 *   <li>{@code PostToPortal} → {@code QUEUED_FOR_RPA → RPA_FAILED}</li>
 * </ul>
 *
 * Kayıt beklenen durumda değilse yalnızca park edilir. Zehirli mesaj (çözülemeyen, beklenmeyen tip) da park edilir,
 * geçiş yapılmaz; gövde geçerli JSON değilse {@code {"raw": "…"}} olarak saklanır. Hiçbir yolda mesaj düşürülmez.
 *
 * <p><b>Eski tur (B-38, A4):</b> {@code PostToPortal} DLQ mesajı kaydın bekleyen komutu değilse (yönetici yeniden
 * işletmiş, mesaj önceki turdan gecikerek gelmiş) yalnızca park edilir; yeni turu {@code RPA_FAILED} yapmaz. Durum
 * koşulu da {@code version} da iki turu ayırt edemez, mesaj kimliği eder.
 */
@Component
public class CommandDeadLetterHandler {

    static final String ACTOR = "dlq";
    static final String UNKNOWN_TYPE = "UNKNOWN";

    private static final Logger log = LoggerFactory.getLogger(CommandDeadLetterHandler.class);

    private record Route(Class<? extends InvoiceMessage> payloadType, DocumentStatus from, DocumentStatus to) {
    }

    private static final Map<String, Route> ROUTES = Map.of(
            DeadLetterQueues.EXTRACT_INVOICE, new Route(ExtractInvoice.class, RECEIVED, NEEDS_REVIEW),
            DeadLetterQueues.CHECK_COMPLIANCE, new Route(CheckCompliance.class, VALIDATED, PENDING_APPROVAL),
            DeadLetterQueues.POST_TO_PORTAL, new Route(PostToPortal.class, QUEUED_FOR_RPA, RPA_FAILED));

    private final DeadLetterRepository deadLetters;
    private final DocumentRepository documents;
    private final StatusTransitions transitions;
    private final InvoiceMessageConverter json;

    public CommandDeadLetterHandler(DeadLetterRepository deadLetters, DocumentRepository documents,
            StatusTransitions transitions, InvoiceMessageConverter json) {
        this.deadLetters = deadLetters;
        this.documents = documents;
        this.transitions = transitions;
        this.json = json;
    }

    public void handle(DeadLetter deadLetter) {
        Route route = ROUTES.get(deadLetter.deadLetterQueue());
        if (route == null) {
            throw new IllegalArgumentException("Komut DLQ'su değil: " + deadLetter.deadLetterQueue());
        }
        UUID messageId = deadLetter.messageIdAsUuid().orElseGet(UUID::randomUUID);
        String type = deadLetter.type() == null ? UNKNOWN_TYPE : deadLetter.type();
        UUID documentId = documentId(deadLetter);

        deadLetters.insertDeadLetter(
                deadLetter.deathQueue() == null ? deadLetter.deadLetterQueue() : deadLetter.deathQueue(),
                type, messageId, documentId, body(deadLetter), json.writeJson(deadLetter.xDeath()));

        if (!route.payloadType().isInstance(deadLetter.payload())) {
            log.error("DLQ mesajı çözülemedi veya beklenmeyen tipte; yalnızca park edildi, durum değişmedi: "
                    + "kuyruk={}, tip={}, neden={}", deadLetter.deadLetterQueue(), type, deadLetter.deathReason());
            return;
        }
        if (route.payloadType() == PostToPortal.class && isFromEarlierRound(documentId, messageId)) {
            log.warn("PostToPortal DLQ mesajı bekleyen komut değil (önceki tur); yalnızca park edildi: messageId={}",
                    messageId);
            return;
        }
        TransitionOutcome outcome = transitions.apply(documentId, route.from(), route.to(),
                Trigger.deadLetter("DLQ:" + type, ACTOR, messageId));
        if (outcome == TransitionOutcome.STALE) {
            log.warn("Kayıt beklenen durumda ({}) değil; DLQ mesajı yalnızca park edildi: kuyruk={}",
                    route.from(), deadLetter.deadLetterQueue());
        }
    }

    /** Bekleyen komut kaydedilmişse ve bu mesaj o değilse önceki turdandır. Kayıt yoksa (eski kayıt) eski davranış. */
    private boolean isFromEarlierRound(UUID documentId, UUID messageId) {
        return documents.findPendingRpaCommand(documentId).map(pending -> !pending.equals(messageId)).orElse(false);
    }

    private static UUID documentId(DeadLetter deadLetter) {
        InvoiceMessage payload = deadLetter.payload();
        return switch (payload) {
            case ExtractInvoice m -> m.documentId();
            case CheckCompliance m -> m.documentId();
            case PostToPortal m -> m.documentId();
            case null, default -> deadLetter.correlationIdAsUuid().orElse(null);
        };
    }

    /** Çözülebilen gövde dönüştürücüyle, çözülemeyen ham haliyle; geçerli JSON değilse {@code {"raw": …}}. */
    private String body(DeadLetter deadLetter) {
        if (deadLetter.payload() != null) {
            return json.toJson(deadLetter.payload());
        }
        String raw = deadLetter.bodyAsText();
        try {
            json.readJson(raw, Object.class);
            return raw;
        } catch (RuntimeException notJson) {
            return json.writeJson(Map.of("raw", raw));
        }
    }
}
