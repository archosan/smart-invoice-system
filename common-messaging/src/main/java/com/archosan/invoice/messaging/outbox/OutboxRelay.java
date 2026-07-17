package com.archosan.invoice.messaging.outbox;

import com.archosan.invoice.messaging.CorrelationScope;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.support.BackgroundLoop;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Yayınlanmamış outbox satırlarını RabbitMQ'ya gönderir (ADR-04, DECISIONS.md §5).
 *
 * <p>Her tur tek transaction'dır: satırlar {@code FOR UPDATE SKIP LOCKED} ile kilitlenir, {@code persistent} ve
 * {@code mandatory} olarak gönderilir, publisher confirm'leri beklenir. Yalnızca ack alan ve geri dönmeyen
 * (return) satırlara {@code published_at} yazılır; nack, return veya zaman aşımı {@code attempts}'i artırır ve satır
 * sonraki turda yeniden denenir. Relay bir satırdan asla vazgeçmez. Kilit sayesinde aynı servisin iki örneği aynı
 * satırı yayınlamaz; ack ile commit arasında çökme tekrar yayın demektir, alıcının inbox'ı karşılar.
 *
 * <p>Broker'a hiç ulaşılamazsa (gönderim istisnası) partinin kalanı gönderilmez ve bu satırların {@code attempts}'i
 * artmaz: kusur satırda değil, altyapıdadır.
 */
public final class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Bir turun sonucu. */
    public record BatchResult(int published, int failed, int notAttempted) {

        static final BatchResult EMPTY = new BatchResult(0, 0, 0);
    }

    record OutboxRow(UUID id, UUID aggregateId, String messageType, String exchange, String routingKey,
                     String payload, int attempts) {
    }

    private record InFlight(OutboxRow row, CorrelationData correlation) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ConnectionFactory connectionFactory;
    private final RabbitTemplate rabbit;
    private final InvoiceMessageConverter converter;
    private final OutboxProperties properties;
    private final MessagingMetrics metrics;

    private final BackgroundLoop loop;

    public OutboxRelay(JdbcClient jdbc, PlatformTransactionManager transactionManager,
            ConnectionFactory connectionFactory, InvoiceMessageConverter converter, OutboxProperties properties) {
        this(jdbc, transactionManager, connectionFactory, converter, properties, MessagingMetrics.NONE);
    }

    public OutboxRelay(JdbcClient jdbc, PlatformTransactionManager transactionManager,
            ConnectionFactory connectionFactory, InvoiceMessageConverter converter, OutboxProperties properties,
            MessagingMetrics metrics) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.connectionFactory = connectionFactory;
        this.converter = converter;
        this.properties = properties;
        this.metrics = metrics;
        this.rabbit = new RabbitTemplate(connectionFactory);
        this.rabbit.setMandatory(true);
        // Return'ler CorrelationData üzerinden okunur; boş callback "callback yok" uyarısını önler.
        this.rabbit.setReturnsCallback(returned -> { });
        // Süren tur bitsin: confirm'leri alınmış satırlar işaretlenmeden kapanmasın.
        this.loop = new BackgroundLoop("outbox-relay", properties.pollInterval(),
                properties.confirmTimeout().plusSeconds(5),
                () -> relayBatch().published() == properties.batchSize());
    }

    /**
     * Tek bir tur çalıştırır. Döngü bunu çağırır; testler doğrudan çağırabilir.
     */
    public BatchResult relayBatch() {
        BatchResult result = transactions.execute(status -> {
            List<OutboxRow> rows = jdbc.sql("""
                            SELECT id, aggregate_id, message_type, exchange, routing_key, payload::text AS payload, attempts
                            FROM outbox
                            WHERE published_at IS NULL
                            ORDER BY created_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                            """)
                    .param("limit", properties.batchSize())
                    .query(OutboxRow.class)
                    .list();
            return rows.isEmpty() ? BatchResult.EMPTY : publish(rows);
        });
        return result == null ? BatchResult.EMPTY : result;
    }

    private BatchResult publish(List<OutboxRow> rows) {
        List<InFlight> inFlight = new ArrayList<>(rows.size());
        for (OutboxRow row : rows) {
            try {
                inFlight.add(new InFlight(row, send(row)));
            } catch (AmqpException e) {
                try (CorrelationScope ignored = scopeFor(row)) {
                    log.warn("Broker'a gönderilemedi, partinin kalanı sonraki tura kaldı: id={}, kalan={}, neden={}",
                            row.id(), rows.size() - inFlight.size(), e.getMessage());
                }
                break;
            }
        }

        List<UUID> published = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        long deadline = System.nanoTime() + properties.confirmTimeout().toNanos();
        for (InFlight pending : inFlight) {
            String failure = awaitConfirm(pending.correlation(), deadline);
            metrics.outboxRelayed(pending.row().messageType(), failure == null);
            if (failure == null) {
                published.add(pending.row().id());
            } else {
                failed.add(pending.row().id());
                OutboxRow row = pending.row();
                try (CorrelationScope ignored = scopeFor(row)) {
                    log.error("Outbox satırı yayınlanamadı, yeniden denenecek: id={}, type={}, exchange={}, "
                                    + "routingKey={}, attempts={}, neden={}",
                            row.id(), row.messageType(), row.exchange(), row.routingKey(), row.attempts() + 1,
                            failure);
                }
            }
        }

        if (!published.isEmpty()) {
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)").param("ids", published).update();
        }
        if (!failed.isEmpty()) {
            jdbc.sql("UPDATE outbox SET attempts = attempts + 1 WHERE id IN (:ids)").param("ids", failed).update();
        }
        return new BatchResult(published.size(), failed.size(), rows.size() - inFlight.size());
    }

    private static CorrelationScope scopeFor(OutboxRow row) {
        return CorrelationScope.forMessage(row.aggregateId().toString(), row.id().toString(), row.messageType());
    }

    private CorrelationData send(OutboxRow row) {
        MessageType type = MessageType.fromTypeName(row.messageType())
                .orElseThrow(() -> new IllegalStateException("Outbox'ta bilinmeyen mesaj tipi: " + row.messageType()));
        MessageEnvelope envelope = MessageEnvelope.of(row.id(), row.aggregateId(), type);
        CorrelationData correlation = new CorrelationData(row.id().toString());
        rabbit.send(row.exchange(), row.routingKey(), converter.toAmqpMessage(envelope, row.payload()), correlation);
        return correlation;
    }

    /** @return hata nedeni; yayın başarılıysa {@code null} */
    private static String awaitConfirm(CorrelationData correlation, long deadlineNanos) {
        CorrelationData.Confirm confirm;
        try {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return "publisher confirm zaman aşımı";
        } catch (ExecutionException e) {
            return "publisher confirm hatası: " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "beklerken kesildi";
        }
        if (!confirm.ack()) {
            return "broker nack: " + confirm.reason();
        }
        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            return "yönlendirilemedi (return): " + returned.getReplyCode() + " " + returned.getReplyText();
        }
        return null;
    }

    @Override
    public void start() {
        if (!connectionFactory.isPublisherConfirms() || !connectionFactory.isPublisherReturns()) {
            throw new IllegalStateException("Outbox relay publisher confirm ve return ister: "
                    + "spring.rabbitmq.publisher-confirm-type=correlated, spring.rabbitmq.publisher-returns=true");
        }
        loop.start();
    }

    @Override
    public void stop() {
        loop.stop();
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return properties.relay().autoStartup();
    }
}
