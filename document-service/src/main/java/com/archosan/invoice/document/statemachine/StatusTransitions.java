package com.archosan.invoice.document.statemachine;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.document.persistence.DeadLetterRepository;
import com.archosan.invoice.document.persistence.DocumentRepository;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Durum makinesi (FR-D3, FR-D4, FR-D9): kaydın durumunu değiştirmenin tek yolu.
 *
 * <ol>
 *   <li>Geçiş {@link TransitionTable}'da yoksa {@link IllegalTransitionException}.</li>
 *   <li>Koşullu {@code UPDATE … WHERE id = ? AND status = ?}; {@code version} artar.</li>
 *   <li>1 satır: {@code status_transitions}'a yazılır, {@link TransitionOutcome#APPLIED}.</li>
 *   <li>0 satır: kayıt artık beklenen durumda değil (geç, sırasız ya da eski komutun sonucu olan olay).
 *       Tetikleyici mesajsa olay {@code dead_letters}'a {@code LATE_EVENT} olarak yazılır ve uyarı loglanır;
 *       {@link TransitionOutcome#STALE}. API tetikleyicisinde yalnızca {@code STALE} döner (çağıran 409 verir).</li>
 * </ol>
 *
 * Alan güncellemeleri (VKN, tutar, portal no) burada değil, geçiş uygulandıktan sonra aynı transaction'da çağıranda
 * yapılır. Her zaman açık bir transaction içinde çağrılır.
 */
@Service
public class StatusTransitions {

    private static final Logger log = LoggerFactory.getLogger(StatusTransitions.class);

    private final DocumentRepository documents;
    private final DeadLetterRepository deadLetters;
    private final InvoiceMessageConverter converter;

    public StatusTransitions(DocumentRepository documents, DeadLetterRepository deadLetters,
            InvoiceMessageConverter converter) {
        this.documents = documents;
        this.deadLetters = deadLetters;
        this.converter = converter;
    }

    public TransitionOutcome apply(UUID documentId, DocumentStatus from, DocumentStatus to, Trigger trigger) {
        return apply(documentId, from, to, trigger, null);
    }

    /**
     * @param expectedVersion verilirse kayıt bu sürümde değilse uygulanmaz ({@code If-Match}, v2); olaylarda
     *                        {@code null}, çünkü olaylar sürüm taşımaz ve durum koşulu yeterlidir
     */
    public TransitionOutcome apply(UUID documentId, DocumentStatus from, DocumentStatus to, Trigger trigger,
            Integer expectedVersion) {
        if (!TransitionTable.isAllowed(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Durum geçişi yalnızca transaction içinde yapılır");
        }

        if (documents.updateStatus(documentId, from, to, expectedVersion) == 1) {
            documents.recordTransition(documentId, from, to, trigger.event(), trigger.messageId(), trigger.actor(),
                    trigger.reason());
            log.info("Durum geçişi: {} → {} ({}, {})", from, to, trigger.event(), trigger.actor());
            return TransitionOutcome.APPLIED;
        }

        if (trigger.message() != null) {
            recordLateEvent(documentId, from, to, trigger);
        }
        return TransitionOutcome.STALE;
    }

    private void recordLateEvent(UUID documentId, DocumentStatus from, DocumentStatus to, Trigger trigger) {
        Trigger.MessageContext message = trigger.message();
        deadLetters.insertLateEvent(message.sourceQueue(), message.envelope().type().typeName(),
                message.envelope().messageId(), documentId, converter.toJson(message.payload()));
        if (documents.exists(documentId)) {
            log.warn("Geç veya sırasız olay, geçiş uygulanmadı ve dead_letters'a kaydedildi: beklenen {} → {}, "
                    + "olay {}", from, to, trigger.event());
        } else {
            log.warn("Olay bilinmeyen bir kayda ait, dead_letters'a kaydedildi: olay {}", trigger.event());
        }
    }
}
