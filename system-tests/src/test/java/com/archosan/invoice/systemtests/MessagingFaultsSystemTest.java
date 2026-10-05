package com.archosan.invoice.systemtests;

import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Mesajlaşma hataları servisler arasında: tekrar yayın, zehirli mesaj, hep hata veren portal (B-28). Park tablosunun
 * {@code source_queue}'su mesajın ilk öldüğü asıl kuyruktur ({@code x-first-death-queue}), DLQ'nun adı değil.
 */
class MessagingFaultsSystemTest extends SystemTest {

    private final InvoiceMessageConverter converter = new InvoiceMessageConverter();

    /**
     * Relay'in "yayınladı ama published_at yazamadan çöktü" durumu: aynı komut ve aynı olay aynı messageId ile ikinci
     * kez yayınlanır. Portal idempotent değildir; ikinci giriş olmamalı, kayıt değişmemeli, geç olay da oluşmamalı.
     */
    @Test
    void republishedCommandAndEventHaveNoSecondEffect() {
        UUID id = upload(Invoices.byFile("invoice-04.pdf"));
        assertThat(api.awaitSettled(id, SETTLE)).isEqualTo("POSTED");
        RabbitTemplate rabbit = stack.rabbit();

        republish(rabbit, documentDb().sql("""
                        SELECT id, payload::text AS payload FROM outbox
                        WHERE aggregate_id = :id AND message_type = 'PostToPortal'
                        """).param("id", id).query(Row.class).single(), id, MessageType.POST_TO_PORTAL);
        republish(rabbit, rpaDb().sql("""
                        SELECT id, payload::text AS payload FROM outbox
                        WHERE aggregate_id = :id AND message_type = 'RpaCompleted'
                        """).param("id", id).query(Row.class).single(), id, MessageType.RPA_COMPLETED);

        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(12)).until(() ->
                submission(id).attemptCount() == 1 && api.status(id).equals("POSTED") && lateEvents(id) == 0);
    }

    /**
     * Zehirli mesaj (çözülemeyen gövde) tüketicide tekrar denenmeden DLQ'ya, oradan document-service'in park
     * tablosuna gider ve kaybolmaz; arkasından gelen fatura etkilenmez.
     */
    @Test
    void poisonMessageIsParkedAndDoesNotBlockNextInvoice() {
        UUID poisonId = UUID.randomUUID();
        stack.rabbit().send("invoice.commands", "extract.invoice", converter.toAmqpMessage(
                MessageEnvelope.of(poisonId, UUID.randomUUID(), MessageType.EXTRACT_INVOICE), "{bozuk"));
        UUID next = upload(Invoices.byFile("invoice-05.pdf"));

        assertThat(api.awaitSettled(next, SETTLE)).isEqualTo("POSTED");
        await().atMost(Duration.ofSeconds(30)).until(() -> documentDb().sql("""
                        SELECT count(*) FROM dead_letters
                        WHERE message_id = :id AND kind = 'DLQ' AND source_queue = 'extraction.extract-invoice.q'
                        """).param("id", poisonId).query(Long.class).single() == 1);
    }

    /**
     * Kabul kriteri: portalda sürekli hata veren fatura teslim limitinden sonra DLQ'ya düşer ({@code RPA_FAILED}),
     * arkasındaki fatura engellenmez ve girilir.
     */
    @Test
    void invoiceFailingAtPortalIsDeadLetteredAndNextOneIsPosted() {
        SyntheticInvoice failing = Invoices.failingAtPortal(llm);
        DocumentApi.Upload upload = api.upload(failing.file(), Invoices.render(failing));
        UUID next = upload(Invoices.byFile("invoice-07.pdf"));

        assertThat(api.awaitSettled(upload.documentId(), SETTLE)).isEqualTo("RPA_FAILED");
        assertThat(api.awaitSettled(next, SETTLE)).isEqualTo("POSTED");
        // İlk teslim + 3 yeniden teslim; hiçbiri portala kaydedilmedi.
        assertThat(submission(upload.documentId())).isEqualTo(new Submission("IN_PROGRESS", null, 4));
        assertThat(documentDb().sql("""
                        SELECT count(*) FROM dead_letters
                        WHERE document_id = :id AND kind = 'DLQ' AND source_queue = 'rpa.post-to-portal.q'
                        """).param("id", upload.documentId()).query(Long.class).single()).isEqualTo(1);
    }

    record Row(UUID id, String payload) {
    }

    private void republish(RabbitTemplate rabbit, Row row, UUID documentId, MessageType type) {
        rabbit.send(type.exchange(), type.routingKey(),
                converter.toAmqpMessage(MessageEnvelope.of(row.id(), documentId, type), row.payload()));
    }

    private long lateEvents(UUID documentId) {
        return documentDb().sql("SELECT count(*) FROM dead_letters WHERE document_id = :id AND kind = 'LATE_EVENT'")
                .param("id", documentId).query(Long.class).single();
    }
}
