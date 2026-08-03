package com.archosan.invoice.document.admin;

import com.archosan.invoice.document.events.RpaDispatch;
import com.archosan.invoice.document.persistence.DeadLetterRepository;
import com.archosan.invoice.document.statemachine.StatusTransitions;
import com.archosan.invoice.document.statemachine.TransitionOutcome;
import com.archosan.invoice.document.statemachine.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static com.archosan.invoice.document.DocumentStatus.QUEUED_FOR_RPA;
import static com.archosan.invoice.document.DocumentStatus.RPA_FAILED;

/**
 * Yöneticinin DLQ işlemleri (B-38, FR-A1, US-10, ADR-18). Yeniden işletme mesajı DLQ'dan kuyruğa taşımaz: kayıt
 * {@code RPA_FAILED → QUEUED_FOR_RPA} geçer, yeni bir {@code PostToPortal} yazılır ve bekleyen komut olur, park kaydı
 * {@code REPROCESSED} olur — tek transaction'da. Yeni tur rpa-service'te kendi deneme bütçesiyle başlar; portalda kayıt
 * oluşmuşsa ön arama onu bulur (US-08). v2'de yalnız RPA yeniden işletilir: durum tablosunda başka yeniden işletme
 * geçişi yoktur.
 */
@Service
public class DeadLetterAdmin {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAdmin.class);

    private final DeadLetterRepository deadLetters;
    private final StatusTransitions transitions;
    private final RpaDispatch rpa;
    private final TransactionTemplate transactions;

    public DeadLetterAdmin(DeadLetterRepository deadLetters, StatusTransitions transitions, RpaDispatch rpa,
            PlatformTransactionManager transactionManager) {
        this.deadLetters = deadLetters;
        this.transitions = transitions;
        this.rpa = rpa;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** @return yeni {@code PostToPortal}'ın kimliği */
    /** @param actor isteği yapan yönetici (B-43); geçişin aktörü */
    public Reprocessed reprocess(UUID deadLetterId, String actor) {
        return transactions.execute(status -> {
            DeadLetterRepository.Detail deadLetter = deadLetters.find(deadLetterId)
                    .orElseThrow(() -> new DeadLetterNotFoundException(deadLetterId));
            if (!"OPEN".equals(deadLetter.status())) {
                throw new DeadLetterConflictException("Park kaydı açık değil: " + deadLetter.status());
            }
            if (!"DLQ".equals(deadLetter.kind()) || !"PostToPortal".equals(deadLetter.messageType())
                    || deadLetter.documentId() == null) {
                throw new DeadLetterConflictException(
                        "Yalnız portal girişi (PostToPortal DLQ kaydı) yeniden işletilebilir: " + deadLetter.kind()
                                + " / " + deadLetter.messageType());
            }
            UUID documentId = deadLetter.documentId();
            Trigger trigger = Trigger.api("ADMIN_REPROCESS", actor, "dead_letter=" + deadLetterId);
            if (transitions.apply(documentId, RPA_FAILED, QUEUED_FOR_RPA, trigger) == TransitionOutcome.STALE) {
                throw new DeadLetterConflictException("Kayıt RPA_FAILED değil: " + documentId);
            }
            UUID commandId = rpa.dispatch(documentId);
            deadLetters.close(deadLetterId, "REPROCESSED");
            log.info("Yeniden işletildi: deadLetter={}, document={}, yeni komut={}", deadLetterId, documentId,
                    commandId);
            return new Reprocessed(deadLetterId, documentId, commandId);
        });
    }

    public void ignore(UUID deadLetterId) {
        transactions.executeWithoutResult(status -> {
            DeadLetterRepository.Detail deadLetter = deadLetters.find(deadLetterId)
                    .orElseThrow(() -> new DeadLetterNotFoundException(deadLetterId));
            if (deadLetters.close(deadLetterId, "IGNORED") == 0) {
                throw new DeadLetterConflictException("Park kaydı açık değil: " + deadLetter.status());
            }
            log.info("Park kaydı yok sayıldı: deadLetter={}", deadLetterId);
        });
    }

    public record Reprocessed(UUID deadLetterId, UUID documentId, UUID commandMessageId) {
    }
}
