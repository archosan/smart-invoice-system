package com.archosan.invoice.messaging.outbox;

import com.archosan.invoice.messaging.Exchanges;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.ReceivedMessage;
import com.archosan.invoice.messaging.SampleMessages;
import com.archosan.invoice.messaging.MessagingIntegrationTest;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxIntegrationTest extends MessagingIntegrationTest {

    /** definitions.json'daki gerçek kuyruk; ExtractInvoice buraya yönlenir. */
    private static final String QUEUE = "extraction.extract-invoice.q";

    @Autowired
    private OutboxWriter writer;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ConnectionFactory connectionFactory;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private OutboxProperties properties;
    @Autowired
    private OutboxCleaner cleaner;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        amqpAdmin.purgeQueue(QUEUE, false);
        jdbc.sql("DELETE FROM outbox").update();
    }

    /** B-48: birikim göstergeleri ve relay sayacı. */
    @Test
    void backlogGaugesAndRelayCounterFollowTheOutbox() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboxBacklogMetrics(jdbc).bindTo(registry);
        OutboxRelay measured = new OutboxRelay(jdbc, transactionManager, connectionFactory, converter, properties,
                new MessagingMetrics(registry));

        UUID id = addInTransaction(SampleMessages.extractInvoice());
        jdbc.sql("UPDATE outbox SET created_at = now() - interval '2 minutes' WHERE id = :id").param("id", id).update();

        assertThat(registry.get("invoice.outbox.pending").gauge().value()).isEqualTo(1);
        assertThat(registry.get("invoice.outbox.oldest.pending.age").gauge().value()).isBetween(119.0, 600.0);

        measured.relayBatch();

        assertThat(registry.get("invoice.outbox.pending").gauge().value()).isZero();
        assertThat(registry.get("invoice.outbox.oldest.pending.age").gauge().value()).isZero();
        assertThat(registry.get("invoice.outbox.relay").tags("type", "ExtractInvoice", "result", "published")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void writerRefusesToWriteOutsideTransaction() {
        assertThatThrownBy(() -> writer.add(SampleMessages.DOCUMENT_ID, SampleMessages.extractInvoice()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void writerStoresRowWithRoutingFromMessageType() {
        UUID messageId = addInTransaction(SampleMessages.extractInvoice());

        var row = jdbc.sql("""
                        SELECT aggregate_id, message_type, exchange, routing_key, payload->>'documentId' AS document_id,
                               published_at, attempts
                        FROM outbox WHERE id = :id
                        """)
                .param("id", messageId)
                .query((rs, n) -> new Object[] {
                        rs.getObject("aggregate_id", UUID.class), rs.getString("message_type"), rs.getString("exchange"),
                        rs.getString("routing_key"), rs.getString("document_id"), rs.getObject("published_at"),
                        rs.getInt("attempts")})
                .single();

        assertThat(row).containsExactly(SampleMessages.DOCUMENT_ID, "ExtractInvoice", "invoice.commands",
                "extract.invoice", SampleMessages.DOCUMENT_ID.toString(), null, 0);
    }

    @Test
    void writerRowIsRolledBackWithBusinessTransaction() {
        tx.executeWithoutResult(status -> {
            writer.add(SampleMessages.DOCUMENT_ID, SampleMessages.extractInvoice());
            status.setRollbackOnly();
        });

        assertThat(countRows()).isZero();
    }

    @Test
    void relayPublishesRowAndMarksItPublished() {
        UUID messageId = addInTransaction(SampleMessages.extractInvoice());

        OutboxRelay.BatchResult result = relay.relayBatch();

        assertThat(result).isEqualTo(new OutboxRelay.BatchResult(1, 0, 0));
        Message received = rabbitTemplate.receive(QUEUE, 5_000);
        assertThat(received).isNotNull();
        MessageProperties props = received.getMessageProperties();
        assertThat(props.getMessageId()).isEqualTo(messageId.toString());
        assertThat(props.getCorrelationId()).isEqualTo(SampleMessages.DOCUMENT_ID.toString());
        assertThat(props.getType()).isEqualTo("ExtractInvoice");
        assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        ReceivedMessage decoded = converter.fromAmqpMessage(received);
        assertThat(decoded.payload()).isEqualTo(SampleMessages.extractInvoice());
        assertThat(publishedAt(messageId)).isNotNull();
        assertThat(attempts(messageId)).isZero();
    }

    @Test
    void relayDoesNotRepublishPublishedRows() {
        addInTransaction(SampleMessages.extractInvoice());
        relay.relayBatch();

        assertThat(relay.relayBatch()).isEqualTo(new OutboxRelay.BatchResult(0, 0, 0));
    }

    @Test
    void unroutableRowStaysUnpublishedAndCountsAttempts() {
        UUID id = insertRaw(Exchanges.COMMANDS, "no.binding.for.this.key");

        assertThat(relay.relayBatch()).isEqualTo(new OutboxRelay.BatchResult(0, 1, 0));
        assertThat(relay.relayBatch()).isEqualTo(new OutboxRelay.BatchResult(0, 1, 0));

        assertThat(publishedAt(id)).isNull();
        assertThat(attempts(id)).isEqualTo(2);
    }

    @Test
    void rowForMissingExchangeIsNackedAndRetried() {
        UUID id = insertRaw("no.such.exchange", MessageType.EXTRACT_INVOICE.routingKey());

        assertThat(relay.relayBatch()).isEqualTo(new OutboxRelay.BatchResult(0, 1, 0));

        assertThat(publishedAt(id)).isNull();
        assertThat(attempts(id)).isEqualTo(1);
    }

    @Test
    void relaySkipsRowsLockedByAnotherRelay() throws Exception {
        UUID id = addInTransaction(SampleMessages.extractInvoice());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> otherRelay = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT id FROM outbox WHERE id = :id FOR UPDATE").param("id", id).query().singleRow();
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        OutboxRelay.BatchResult whileLocked = relay.relayBatch();
        release.countDown();
        otherRelay.get(10, TimeUnit.SECONDS);

        assertThat(whileLocked).isEqualTo(new OutboxRelay.BatchResult(0, 0, 0));
        assertThat(relay.relayBatch()).isEqualTo(new OutboxRelay.BatchResult(1, 0, 0));
    }

    @Test
    void relayTakesAtMostBatchSizeRowsPerRound() {
        OutboxRelay smallBatches = new OutboxRelay(jdbc, transactionManager, connectionFactory, converter,
                new OutboxProperties(Duration.ofMillis(500), 2, Duration.ofSeconds(5), properties.relay(),
                        properties.retention(), properties.cleanupInterval(), properties.cleanupBatchSize(),
                        properties.cleanup()));
        for (int i = 0; i < 3; i++) {
            addInTransaction(new ExtractInvoice(UUID.randomUUID(), "file:///data/documents/" + i + ".pdf", "h" + i));
        }

        assertThat(smallBatches.relayBatch().published()).isEqualTo(2);
        assertThat(smallBatches.relayBatch().published()).isEqualTo(1);
        assertThat(countUnpublished()).isZero();
    }

    /** A7: yalnız yayınlanmış ve saklama süresi dolmuş satırlar silinir; yayınlanmamışa yaşı ne olursa olsun dokunulmaz. */
    @Test
    void cleanerDeletesOnlyExpiredPublishedRowsInChunks() {
        for (int i = 0; i < 5; i++) {
            publishedDaysAgo(insertRaw(Exchanges.COMMANDS, "extract.invoice"), 8);
        }
        UUID recent = insertRaw(Exchanges.COMMANDS, "extract.invoice");
        publishedDaysAgo(recent, 6);
        UUID unpublished = insertRaw(Exchanges.COMMANDS, "extract.invoice");
        jdbc.sql("UPDATE outbox SET created_at = now() - interval '30 days' WHERE id = :id")
                .param("id", unpublished).update();
        OutboxCleaner smallChunks = new OutboxCleaner(jdbc, new OutboxProperties(properties.pollInterval(),
                properties.batchSize(), properties.confirmTimeout(), properties.relay(), Duration.ofDays(7),
                Duration.ofHours(1), 2, properties.cleanup()));

        assertThat(smallChunks.cleanUp()).isEqualTo(5);
        assertThat(countRows()).isEqualTo(2);
        assertThat(publishedAt(recent)).isNotNull();
        assertThat(publishedAt(unpublished)).isNull();
        assertThat(cleaner.cleanUp()).isZero();
    }

    /** Bekleme odası aynı kimlikli eski satırı yeniden yazar; yeniden yayın bekleyen satır silinmez. */
    @Test
    void cleanerKeepsRowRepublishedThroughWaitingRoom() {
        UUID id = addInTransaction(SampleMessages.extractInvoice());
        relay.relayBatch();
        rabbitTemplate.receive(QUEUE, 5_000);
        publishedDaysAgo(id, 8);

        tx.executeWithoutResult(status -> writer.republish(
                MessageEnvelope.of(id, SampleMessages.DOCUMENT_ID, MessageType.EXTRACT_INVOICE),
                SampleMessages.extractInvoice(), Exchanges.COMMANDS, "extract.invoice"));

        assertThat(cleaner.cleanUp()).isZero();
        assertThat(countUnpublished()).isEqualTo(1);
        assertThat(relay.relayBatch().published()).isEqualTo(1);
    }

    @Test
    void runningRelayPublishesWithoutExplicitCalls() throws Exception {
        UUID id = addInTransaction(SampleMessages.extractInvoice());

        relay.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (publishedAt(id) == null && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
        } finally {
            relay.stop();
        }

        assertThat(publishedAt(id)).isNotNull();
        assertThat(relay.isRunning()).isFalse();
    }

    private UUID addInTransaction(ExtractInvoice payload) {
        return tx.execute(status -> writer.add(payload.documentId(), payload));
    }

    private UUID insertRaw(String exchange, String routingKey) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_id, message_type, exchange, routing_key, payload)
                        VALUES (:id, :aggregateId, 'ExtractInvoice', :exchange, :routingKey, CAST(:payload AS jsonb))
                        """)
                .param("id", id)
                .param("aggregateId", SampleMessages.DOCUMENT_ID)
                .param("exchange", exchange)
                .param("routingKey", routingKey)
                .param("payload", converter.toJson(SampleMessages.extractInvoice()))
                .update();
        return id;
    }

    private void publishedDaysAgo(UUID id, int days) {
        jdbc.sql("UPDATE outbox SET published_at = now() - make_interval(days => :days) WHERE id = :id")
                .param("days", days)
                .param("id", id)
                .update();
    }

    private OffsetDateTime publishedAt(UUID id) {
        return jdbc.sql("SELECT published_at FROM outbox WHERE id = :id").param("id", id)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).list().getFirst();
    }

    private int attempts(UUID id) {
        return jdbc.sql("SELECT attempts FROM outbox WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    private long countRows() {
        return jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single();
    }

    private long countUnpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
