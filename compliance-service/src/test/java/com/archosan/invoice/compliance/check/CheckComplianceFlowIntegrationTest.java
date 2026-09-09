package com.archosan.invoice.compliance.check;

import com.archosan.invoice.compliance.ComplianceIntegrationTest;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.message.InvoiceLine;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code CheckCompliance} gerçek kuyruğa gönderilir, gerçek dinleyici (uzun iş) işler; olay outbox'tan okunur ve
 * relay'in onu yayınladığı ({@code published_at}) doğrulanır. Sözleşmesiz tedarikçi: {@code NO_CONTRACT} (B-46).
 */
class CheckComplianceFlowIntegrationTest extends ComplianceIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void supplierWithoutContractGetsNoContractAndCheckIsRecorded() {
        UUID documentId = UUID.randomUUID();

        rabbitTemplate.send("invoice.commands", "compliance.check", command(UUID.randomUUID(), documentId));

        ComplianceCompleted completed = awaitEvent(documentId);
        assertThat(completed).isEqualTo(new ComplianceCompleted(documentId, ComplianceCompleted.Result.NO_CONTRACT,
                null, List.of(new ComplianceCompleted.Finding(ComplianceCompleted.Check.CONTRACT_VALIDITY, null, null,
                        "2026-09-30", null, true))));
        assertThat(jdbc.sql("SELECT result || '|' || coalesce(model, '-') FROM compliance_checks WHERE document_id = :id")
                .param("id", documentId).query(String.class).single()).isEqualTo("NO_CONTRACT|-");
        await().atMost(TIMEOUT).until(() -> jdbc.sql(
                        "SELECT published_at IS NOT NULL FROM outbox WHERE aggregate_id = :id")
                .param("id", documentId).query(Boolean.class).single());
    }

    @Test
    void redeliveredCommandProducesOneEvent() {
        UUID documentId = UUID.randomUUID();
        Message message = command(UUID.randomUUID(), documentId);

        rabbitTemplate.send("invoice.commands", "compliance.check", message);
        awaitEvent(documentId);
        rabbitTemplate.send("invoice.commands", "compliance.check", message);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> outboxCount(documentId) == 1 && inboxCount() == 1);
    }

    private Message command(UUID messageId, UUID documentId) {
        return converter.toAmqpMessage(MessageEnvelope.of(messageId, documentId, MessageType.CHECK_COMPLIANCE),
                new CheckCompliance(documentId, "1234567890", LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 30),
                        List.of(new InvoiceLine("Rulman 6204 ZZ", new BigDecimal("4"), new BigDecimal("125.50"), 20)),
                        new BigDecimal("502.00"), new BigDecimal("100.40"), new BigDecimal("602.40"), "TRY"));
    }

    private ComplianceCompleted awaitEvent(UUID documentId) {
        await().atMost(TIMEOUT).until(() -> outboxCount(documentId) == 1);
        String payload = jdbc.sql("""
                        SELECT payload::text FROM outbox
                        WHERE aggregate_id = :id AND message_type = 'ComplianceCompleted'
                        """)
                .param("id", documentId).query(String.class).single();
        return converter.readJson(payload, ComplianceCompleted.class);
    }

    private long outboxCount(UUID documentId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id").param("id", documentId)
                .query(Long.class).single();
    }

    private long inboxCount() {
        return jdbc.sql("SELECT count(*) FROM inbox WHERE consumer = :queue")
                .param("queue", ComplianceListenerConfiguration.QUEUE).query(Long.class).single();
    }
}
