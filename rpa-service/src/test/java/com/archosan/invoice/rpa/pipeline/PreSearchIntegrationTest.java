package com.archosan.invoice.rpa.pipeline;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.message.RpaCompleted;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Portalda ön arama (B-35, FR-R3, US-08): her girişten önce fatura numarasıyla aranır, sonuç sayfaları dolaşılır; fatura
 * no ve VKN'si tam eşleşen kayıt varsa form doldurulmaz, {@code FOUND_EXISTING} + {@code RpaCompleted(foundExisting)}.
 * Gerçek Chromium, JVM içi gerçek mock-portal.
 */
class PreSearchIntegrationTest extends RpaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String VKN = "4810293756";

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;

    /** US-08: bot formu gönderdi, kayıt numarasını okumadan öldü; yeniden teslimde ön arama kaydı bulur. */
    @Test
    void invoiceLeftInPortalByCrashedAttemptIsFoundNotEnteredAgain() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = "US08-" + UUID.randomUUID();
        String refNo = MockPortal.enter(VKN, invoiceNo);
        jdbc.sql("""
                        INSERT INTO portal_submissions (document_id, supplier_vkn, invoice_no, status, attempt_count)
                        VALUES (:id, :vkn, :invoiceNo, 'IN_PROGRESS', 1)
                        """).param("id", documentId).param("vkn", VKN).param("invoiceNo", invoiceNo).update();

        send(documentId, VKN, invoiceNo);

        RpaCompleted completed = awaitEvent(documentId);
        assertThat(completed.foundExisting()).isTrue();
        assertThat(completed.portalRefNo()).isEqualTo(refNo);
        assertThat(MockPortal.invoices(invoiceNo)).hasSize(1);
        assertThat(submission(documentId)).isEqualTo("FOUND_EXISTING " + refNo + " 2");
    }

    @Test
    void sameInvoiceNoOfAnotherSupplierIsNotAMatch() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = "VKN-" + UUID.randomUUID();
        MockPortal.enter("9999999999", invoiceNo);

        send(documentId, VKN, invoiceNo);

        RpaCompleted completed = awaitEvent(documentId);
        assertThat(completed.foundExisting()).isFalse();
        assertThat(MockPortal.invoices(invoiceNo)).extracting(i -> i.supplierVkn())
                .containsExactlyInAnyOrder("9999999999", VKN);
    }

    /** Arama "içerir" ile çalışır; hedef en eski kayıt olduğu için sonuçların son sayfasındadır (sayfa başına 10). */
    @Test
    void existingInvoiceOnALaterResultPageIsFound() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = "PAGE-" + UUID.randomUUID().toString().substring(0, 8);
        String refNo = MockPortal.enter(VKN, invoiceNo);
        for (int i = 1; i <= 23; i++) {
            MockPortal.enter(VKN, invoiceNo + "-" + i);
        }

        send(documentId, VKN, invoiceNo);

        RpaCompleted completed = awaitEvent(documentId);
        assertThat(completed.foundExisting()).isTrue();
        assertThat(completed.portalRefNo()).isEqualTo(refNo);
        assertThat(MockPortal.invoices(invoiceNo)).hasSize(1);
    }

    private void send(UUID documentId, String vkn, String invoiceNo) {
        rabbitTemplate.send("invoice.commands", "rpa.post", converter.toAmqpMessage(
                MessageEnvelope.of(UUID.randomUUID(), documentId, MessageType.POST_TO_PORTAL),
                new PostToPortal(documentId, vkn, "Anadolu Rulman A.Ş.", invoiceNo, LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 1), new BigDecimal("5100.00"), new BigDecimal("850.00"), "TRY")));
    }

    private RpaCompleted awaitEvent(UUID documentId) {
        await().atMost(TIMEOUT).until(() -> jdbc.sql("""
                        SELECT count(*) FROM outbox WHERE aggregate_id = :id AND message_type = 'RpaCompleted'
                        """).param("id", documentId).query(Long.class).single() == 1);
        return converter.readJson(jdbc.sql("""
                        SELECT payload::text FROM outbox WHERE aggregate_id = :id AND message_type = 'RpaCompleted'
                        """).param("id", documentId).query(String.class).single(), RpaCompleted.class);
    }

    private String submission(UUID documentId) {
        return jdbc.sql("""
                        SELECT status || ' ' || portal_ref_no || ' ' || attempt_count FROM portal_submissions
                        WHERE document_id = :id
                        """).param("id", documentId).query(String.class).single();
    }
}
