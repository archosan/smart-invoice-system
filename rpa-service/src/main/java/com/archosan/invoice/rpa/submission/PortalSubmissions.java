package com.archosan.invoice.rpa.submission;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/** {@code portal_submissions}: portala giriş kaydı (DECISIONS.md §4.4). */
@Repository
public class PortalSubmissions {

    /** {@link #begin} sonucu. */
    public sealed interface Start {
    }

    /**
     * Portala girilecek.
     *
     * @param attempt      bu belgenin bütün turlardaki kaçıncı denemesi ({@code rpa_attempts.attempt_no})
     * @param roundAttempt bu komutun (turun) kaçıncı denemesi; deneme sınırı buna uygulanır (B-38)
     */
    public record Started(int attempt, int roundAttempt) implements Start {
    }

    /**
     * Belge zaten tamamlanmış ({@code SUBMITTED} ya da {@code FOUND_EXISTING}); portala dokunulmaz.
     *
     * @param foundExisting kayıt ön aramada bulunmuştu (B-35)
     */
    public record AlreadySubmitted(String portalRefNo, boolean foundExisting) implements Start {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PortalSubmissions(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Portala dokunmadan önce, kendi kısa transaction'ında: kayıt yoksa {@code IN_PROGRESS} açar, varsa deneme
     * sayısını artırır ve commit eder. Belge zaten tamamlanmışsa ({@code SUBMITTED}, {@code FOUND_EXISTING}) kaydı
     * değiştirmez.
     *
     * <p>{@code IN_PROGRESS} kalmış bir kayıt (giriş sırasında çökme, US-08) yeniden denenir; portalda kayıt oluşmuşsa
     * ön arama onu bulur (B-35).
     */
    public Start begin(UUID documentId, UUID commandId, String supplierVkn, String invoiceNo) {
        return transaction.execute(status -> {
            Optional<AlreadySubmitted> done = jdbc.sql("""
                            SELECT portal_ref_no, status = 'FOUND_EXISTING' AS found_existing FROM portal_submissions
                            WHERE document_id = :id AND status IN ('SUBMITTED', 'FOUND_EXISTING')
                            FOR UPDATE
                            """)
                    .param("id", documentId)
                    .query(AlreadySubmitted.class)
                    .optional();
            if (done.isPresent()) {
                return done.get();
            }
            // Yeni komut (yeniden işletme) yeni tur başlatır; bekleme odasından dönen aynı komut turu sürdürür.
            return jdbc.sql("""
                            INSERT INTO portal_submissions
                                (document_id, supplier_vkn, invoice_no, status, attempt_count, command_id,
                                 round_attempts)
                            VALUES (:id, :vkn, :invoiceNo, 'IN_PROGRESS', 1, :command, 1)
                            ON CONFLICT (document_id) DO UPDATE
                                SET attempt_count = portal_submissions.attempt_count + 1,
                                    round_attempts =
                                        CASE WHEN portal_submissions.command_id IS NOT DISTINCT FROM :command
                                             THEN portal_submissions.round_attempts + 1 ELSE 1 END,
                                    command_id = :command,
                                    updated_at = now()
                            RETURNING attempt_count AS attempt, round_attempts AS round_attempt
                            """)
                    .param("id", documentId)
                    .param("vkn", supplierVkn)
                    .param("invoiceNo", invoiceNo)
                    .param("command", commandId)
                    .query(Started.class)
                    .single();
        });
    }

    /** {@code IN_PROGRESS → SUBMITTED}; çağıranın transaction'ında (inbox ve outbox ile birlikte). */
    public void markSubmitted(UUID documentId, String portalRefNo) {
        complete(documentId, "SUBMITTED", portalRefNo);
    }

    /** {@code IN_PROGRESS → FOUND_EXISTING}: ön arama kaydı buldu, form doldurulmadı (B-35). */
    public void markFoundExisting(UUID documentId, String portalRefNo) {
        complete(documentId, "FOUND_EXISTING", portalRefNo);
    }

    private void complete(UUID documentId, String status, String portalRefNo) {
        int updated = jdbc.sql("""
                        UPDATE portal_submissions
                        SET status = :status, portal_ref_no = :refNo, updated_at = now()
                        WHERE document_id = :id AND status = 'IN_PROGRESS'
                        """)
                .param("id", documentId)
                .param("status", status)
                .param("refNo", portalRefNo)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("IN_PROGRESS kaydı bulunamadı: documentId=" + documentId);
        }
    }
}
