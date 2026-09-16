package com.archosan.invoice.document.deadletter;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.consumer.InvoiceListenerContainers;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Komut gerçek kuyruğunda kalıcı hatayla reddedilip broker tarafından gerçekten DLQ'ya düşürülür; document-service'in
 * DLQ dinleyicisi onu park eder ve kaydın durumunu ilerletir (B-14).
 */
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeadLetterFlowIntegrationTest extends DocumentIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final List<String> COMMAND_QUEUES = List.of(
            "extraction.extract-invoice.q", "compliance.check-compliance.q", "rpa.post-to-portal.q");

    @Autowired
    private InvoiceMessageListenerFactory listeners;
    @Autowired
    private ConnectionFactory connectionFactory;
    @Autowired
    private OutboxWriter outbox;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private EventDeadLetterMonitor monitor;

    private final List<SimpleMessageListenerContainer> rejectingConsumers = new ArrayList<>();

    @BeforeAll
    void startRejectingCommandConsumers() {
        for (String queue : COMMAND_QUEUES) {
            amqpAdmin.purgeQueue(queue, false);
        }
        // Diğer servislerin yerine: her komutu kalıcı hatayla reddeder, broker DLQ'ya düşürür.
        rejectingConsumers.add(rejecting("extraction.extract-invoice.q", ExtractInvoice.class));
        rejectingConsumers.add(rejecting("compliance.check-compliance.q", CheckCompliance.class));
        rejectingConsumers.add(rejecting("rpa.post-to-portal.q", PostToPortal.class));
    }

    @AfterAll
    void stopRejectingConsumers() {
        rejectingConsumers.forEach(SimpleMessageListenerContainer::stop);
    }

    @BeforeEach
    void purgeEventDeadLetterQueues() {
        for (String queue : DeadLetterQueues.EVENT_DLQS) {
            amqpAdmin.purgeQueue(queue, false);
        }
    }

    @Test
    void extractInvoiceInDeadLetterQueueMovesRecordToNeedsReview() {
        UUID id = insertDocument(DocumentStatus.RECEIVED);
        UUID messageId = publish(id, new ExtractInvoice(id, "file:///data/documents/x.pdf", "ab"));

        awaitStatus(id, "NEEDS_REVIEW");

        assertThat(transitions(id)).containsExactly("RECEIVED>NEEDS_REVIEW:DLQ:ExtractInvoice:dlq:" + messageId);
        assertThat(jdbc.sql("""
                        SELECT kind || '|' || status || '|' || source_queue || '|' || message_type || '|' || message_id
                               || '|' || document_id || '|' || (body->>'storageUri') || '|'
                               || (x_death->0->>'queue') || '|' || (x_death->0->>'reason')
                        FROM dead_letters
                        """).query(String.class).single())
                .isEqualTo("DLQ|OPEN|extraction.extract-invoice.q|ExtractInvoice|" + messageId + "|" + id
                        + "|file:///data/documents/x.pdf|extraction.extract-invoice.q|rejected");
    }

    @Test
    void checkComplianceInDeadLetterQueueMovesRecordToPendingApproval() {
        UUID id = insertDocument(DocumentStatus.VALIDATED);

        publish(id, new CheckCompliance(id, "1234567890", null, null, List.of(), null, null, null, "TRY"));

        awaitStatus(id, "PENDING_APPROVAL");
        assertThat(transitions(id)).singleElement().asString().startsWith("VALIDATED>PENDING_APPROVAL:DLQ:CheckCompliance:dlq:");
    }

    @Test
    void postToPortalInDeadLetterQueueMovesRecordToRpaFailed() {
        UUID id = insertDocument(DocumentStatus.QUEUED_FOR_RPA);

        publish(id, postToPortal(id));

        awaitStatus(id, "RPA_FAILED");
        assertThat(count("dead_letters")).isEqualTo(1);
    }

    /**
     * B-38, A4: yönetici yeniden işlettikten sonra önceki turdan gecikerek gelen DLQ mesajı (bekleyen komut değil)
     * yalnızca park edilir; yeni tur {@code QUEUED_FOR_RPA}'da kalır.
     */
    @Test
    void postToPortalFromEarlierRoundIsOnlyParked() {
        UUID id = insertDocument(DocumentStatus.QUEUED_FOR_RPA);
        setPendingCommand(id, UUID.randomUUID());

        sendToPortalDeadLetterQueue(id, UUID.randomUUID());

        await().atMost(TIMEOUT).until(() -> count("dead_letters") == 1);
        assertThat(status(id)).isEqualTo("QUEUED_FOR_RPA");
        assertThat(count("status_transitions")).isZero();
    }

    @Test
    void postToPortalOfCurrentRoundMovesRecordToRpaFailed() {
        UUID id = insertDocument(DocumentStatus.QUEUED_FOR_RPA);
        UUID current = UUID.randomUUID();
        setPendingCommand(id, current);

        sendToPortalDeadLetterQueue(id, current);

        awaitStatus(id, "RPA_FAILED");
    }

    @Test
    void recordNotInExpectedStateIsOnlyParked() {
        UUID id = insertDocument(DocumentStatus.POSTED);

        publish(id, postToPortal(id));

        await().atMost(TIMEOUT).until(() -> count("dead_letters") == 1);
        assertThat(jdbc.sql("SELECT kind FROM dead_letters").query(String.class).single()).isEqualTo("DLQ");
        assertThat(status(id)).isEqualTo("POSTED");
        assertThat(count("status_transitions")).isZero();
    }

    @Test
    void unparsableMessageIsParkedRawAndNotLost() {
        MessageProperties props = new MessageProperties();
        props.setMessageId(UUID.randomUUID().toString());
        props.setType("ExtractInvoice");
        props.setContentType("application/json");
        rabbitTemplate.send("", DeadLetterQueues.EXTRACT_INVOICE,
                new Message("bu json değil".getBytes(StandardCharsets.UTF_8), props));

        await().atMost(TIMEOUT).until(() -> count("dead_letters") == 1);
        assertThat(jdbc.sql("SELECT message_type || '|' || (body->>'raw') FROM dead_letters").query(String.class)
                .single()).isEqualTo("ExtractInvoice|bu json değil");
        assertThat(amqpAdmin.getQueueInfo(DeadLetterQueues.EXTRACT_INVOICE).getMessageCount()).isZero();
        assertThat(count("status_transitions")).isZero();
    }

    @Test
    void sameDeadLetterTwiceIsParkedOnce() {
        UUID id = insertDocument(DocumentStatus.POSTED);
        Message message = converter.toAmqpMessage(
                MessageEnvelope.of(UUID.randomUUID(), id, MessageType.POST_TO_PORTAL), postToPortal(id));

        rabbitTemplate.send("", DeadLetterQueues.POST_TO_PORTAL, message);
        rabbitTemplate.send("", DeadLetterQueues.POST_TO_PORTAL, message);

        await().atMost(TIMEOUT).until(() ->
                amqpAdmin.getQueueInfo(DeadLetterQueues.POST_TO_PORTAL).getMessageCount() == 0 && count("inbox") == 1);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> count("dead_letters") == 1);
    }

    @Test
    void monitorLogsErrorForNonEmptyEventDeadLetterQueue(CapturedOutput output) {
        assertThat(monitor.check()).containsOnlyKeys(DeadLetterQueues.EVENT_DLQS).doesNotContainValue(-1L)
                .allSatisfy((queue, depth) -> assertThat(depth).isZero());
        assertThat(output.getOut()).doesNotContain("Olay DLQ'sunda");

        rabbitTemplate.send("", "document.rpa-events.dlq", new Message("{}".getBytes(StandardCharsets.UTF_8)));
        await().atMost(TIMEOUT).until(() -> monitor.check().get("document.rpa-events.dlq") == 1L);

        assertThat(output.getOut()).contains("Olay DLQ'sunda 1 mesaj var: kuyruk=document.rpa-events.dlq");
    }

    private SimpleMessageListenerContainer rejecting(String queue, Class<? extends InvoiceMessage> type) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        SimpleMessageListenerContainer container = InvoiceListenerContainers.create(connectionFactory,
                listeners.forQueue(queue).on((Class) type, (envelope, message) -> {
                    throw new AmqpRejectAndDontRequeueException("test: komut işlenemedi");
                }).build(), 1, 1);
        container.start();
        return container;
    }

    private UUID publish(UUID documentId, InvoiceMessage command) {
        return new TransactionTemplate(transactionManager).execute(status -> outbox.add(documentId, command));
    }

    private static PostToPortal postToPortal(UUID id) {
        return new PostToPortal(id, "1234567890", "ACME", "F-1", null, null, new BigDecimal("120.00"),
                new BigDecimal("20.00"), "TRY");
    }

    private void setPendingCommand(UUID documentId, UUID commandId) {
        jdbc.sql("UPDATE documents SET pending_rpa_command_id = :command WHERE id = :id")
                .param("id", documentId).param("command", commandId).update();
    }

    /** Kimliği seçilmiş {@code PostToPortal}'ı doğrudan DLQ'ya koyar (zamanlamaya bağlı olmasın). */
    private void sendToPortalDeadLetterQueue(UUID documentId, UUID messageId) {
        rabbitTemplate.send("", DeadLetterQueues.POST_TO_PORTAL, converter.toAmqpMessage(
                MessageEnvelope.of(messageId, documentId, MessageType.POST_TO_PORTAL), postToPortal(documentId)));
    }

    private UUID insertDocument(DocumentStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status)
                        VALUES (:id, :sha, 'file:///data/documents/x.pdf', :status)
                        """)
                .param("id", id).param("sha", (id.toString() + id).replace("-", "").substring(0, 64))
                .param("status", status.name()).update();
        return id;
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(TIMEOUT).until(() -> expected.equals(status(id)));
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM documents WHERE id = :id").param("id", id).query(String.class).single();
    }

    private List<String> transitions(UUID id) {
        return jdbc.sql("""
                        SELECT from_status || '>' || to_status || ':' || trigger_event || ':' || actor || ':'
                               || coalesce(message_id::text, '')
                        FROM status_transitions WHERE document_id = :id ORDER BY id
                        """).param("id", id).query(String.class).list();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
