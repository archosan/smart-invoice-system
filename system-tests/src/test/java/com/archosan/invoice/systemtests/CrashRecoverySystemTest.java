package com.archosan.invoice.systemtests;

import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kabul kriteri: mesaj yayını sırasında öldürülen servis yeniden başladığında hiçbir fatura ara durumda takılı kalmaz
 * (B-28). Öldürme gerçek çökmedir ({@code docker kill}, SIGKILL): kapanış kancası, graceful shutdown yok.
 */
class CrashRecoverySystemTest extends SystemTest {

    /**
     * Yüklemeler commit edildikten hemen sonra, outbox'taki {@code ExtractInvoice}'lar yayınlanırken orkestratör
     * ölür; bu sırada diğer servislerin olayları onun kuyruklarında birikir. Yeniden başlayınca relay kalan satırları
     * yayınlar, olaylar işlenir; her fatura POSTED olur ve portala bir kez girilir.
     */
    @Test
    void documentServiceKilledWhilePublishingLeavesNothingStuck() {
        List<UUID> ids = List.of("invoice-01.pdf", "invoice-02.pdf", "invoice-04.pdf", "invoice-05.pdf",
                "invoice-07.pdf", "invoice-08.pdf").stream().map(file -> upload(Invoices.byFile(file))).toList();

        stack.kill("document-service");
        stack.restart("document-service");

        ids.forEach(id -> assertThat(api.awaitSettled(id, SETTLE)).as(id.toString()).isEqualTo("POSTED"));
        ids.forEach(id -> {
            assertThat(submission(id).attemptCount()).as(id.toString()).isEqualTo(1);
            assertThat(documentDb().sql("""
                            SELECT count(*) FROM outbox WHERE aggregate_id = :id AND published_at IS NULL
                            """).param("id", id).query(Long.class).single()).as(id.toString()).isZero();
        });
    }

    /**
     * extraction-service LLM çağrısının ortasında ölür: mesaj ack edilmemiştir, broker onu yeniden teslim eder.
     * Yeniden başlayan servis işi baştan yapar; iki fatura da POSTED olur. (Ölen örneğin tuttuğu {@code sem:llm} izni
     * kirası dolana kadar kullanılamaz; kalan izinle devam edilir.)
     */
    @Test
    void extractionServiceKilledDuringLlmCallLeavesNothingStuck() {
        llm.delay(Duration.ofSeconds(4));
        int before = llm.requestCount();
        List<UUID> ids = List.of(upload(Invoices.byFile("invoice-08.pdf")), upload(Invoices.byFile("invoice-02.pdf")));
        await().atMost(Duration.ofMinutes(1)).until(() -> llm.requestCount() > before);

        stack.kill("extraction-service");
        llm.delay(Duration.ZERO);
        stack.restart("extraction-service");

        ids.forEach(id -> assertThat(api.awaitSettled(id, SETTLE)).as(id.toString()).isEqualTo("POSTED"));
    }

    /**
     * rpa-service portal kuyruğundaki fatura sırasında ölür. Fatura takılı kalmaz, POSTED olur. Formun gönderildiği an
     * için ayrı, kesin bir test var ({@link #rpaServiceKilledAfterPortalSavedButBeforeReplyEntersInvoiceOnce}).
     */
    @Test
    void rpaServiceKilledAroundPortalEntryLeavesNothingStuck() {
        UUID id = upload(Invoices.byFile("invoice-01.pdf"));
        await().atMost(SETTLE).pollInterval(Duration.ofMillis(200))
                .until(() -> List.of("QUEUED_FOR_RPA", "POSTED").contains(api.status(id)));

        stack.kill("rpa-service");
        stack.restart("rpa-service");

        assertThat(api.awaitSettled(id, SETTLE)).isEqualTo("POSTED");
        assertThat(submission(id).status()).isIn("SUBMITTED", "FOUND_EXISTING");
    }

    /**
     * US-08, B-35 (NFR-04): portal faturayı kaydeder ama yanıtı geciktirir; bot tam bu arada öldürülür — portalda kayıt
     * var, sistemde yok. Yeniden başlayan bot (ölen örneğin kilidi kira dolunca düşer, o arada mesaj bekleme odasında
     * bekler) ön aramada kaydı bulur: form yeniden doldurulmaz, portalda tek kayıt kalır, sonuç {@code FOUND_EXISTING}.
     */
    @Test
    void rpaServiceKilledAfterPortalSavedButBeforeReplyEntersInvoiceOnce() {
        SyntheticInvoice slow = Invoices.slowAtPortal(llm);
        String invoiceNo = slow.fields().invoiceNo();
        DocumentApi.Upload upload = api.upload(slow.file(), Invoices.render(slow));
        // Portal kaydetti, yanıtı 20 sn bekletiyor.
        await().atMost(SETTLE).pollInterval(Duration.ofMillis(500))
                .until(() -> stack.logs("mock-portal").contains("invoiceNo=" + invoiceNo));

        stack.kill("rpa-service");
        stack.restart("rpa-service");

        assertThat(api.awaitSettled(upload.documentId(), Duration.ofMinutes(5))).isEqualTo("POSTED");
        assertThat(submission(upload.documentId())).satisfies(s -> {
            assertThat(s.status()).isEqualTo("FOUND_EXISTING");
            assertThat(s.attemptCount()).isGreaterThanOrEqualTo(2);
        });
        assertThat(api.get(upload.documentId()).path("portalRefNo").asString())
                .isEqualTo(submission(upload.documentId()).portalRefNo());
        // Portal faturayı bir kez kaydetti.
        assertThat(stack.logs("mock-portal").split("invoiceNo=" + invoiceNo, -1)).hasSize(2);
    }
}
