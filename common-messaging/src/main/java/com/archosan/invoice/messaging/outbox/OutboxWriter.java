package com.archosan.invoice.messaging.outbox;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * Giden mesajı {@code outbox} tablosuna yazar. Servislerin mesaj göndermesinin tek yolu budur; yayını
 * {@link OutboxRelay} yapar.
 *
 * <p>Yalnızca açık bir iş transaction'ı içinde çağrılabilir ({@code Propagation.MANDATORY} karşılığı): mesaj iş
 * verisiyle birlikte commit olmalı ya da birlikte geri alınmalıdır.
 */
public final class OutboxWriter {

    private final JdbcClient jdbc;
    private final InvoiceMessageConverter converter;

    public OutboxWriter(JdbcClient jdbc, InvoiceMessageConverter converter) {
        this.jdbc = jdbc;
        this.converter = converter;
    }

    /**
     * @param aggregateId mesajın ait olduğu kayıt (documentId; sözleşme mesajlarında contractId). Yayında
     *                    {@code correlation_id} olur.
     * @return üretilen {@code messageId}; alıcının inbox anahtarı
     */
    public UUID add(UUID aggregateId, InvoiceMessage payload) {
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        requireTransaction();
        MessageType type = MessageType.of(payload);
        UUID messageId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_id, message_type, exchange, routing_key, payload)
                        VALUES (:id, :aggregateId, :messageType, :exchange, :routingKey, CAST(:payload AS jsonb))
                        """)
                .param("id", messageId)
                .param("aggregateId", aggregateId)
                .param("messageType", type.typeName())
                .param("exchange", type.exchange())
                .param("routingKey", type.routingKey())
                .param("payload", converter.toJson(payload))
                .update();
        return messageId;
    }

    /**
     * Alınmış bir mesajı <b>aynı {@code messageId}</b> ve {@code correlationId} ile başka bir hedefe yeniden yayınlar;
     * bekleme odası için (B-34): mesaj TTL dolunca asıl kuyruğuna aynı kimlikle döner ve alıcının inbox'ı onu yeni
     * mesaj gibi işler. Dinleyici tarafında {@code InboxCompletion.defer} ile birlikte kullanılır (inbox'a yazılmaz).
     *
     * <p>Mesaj birden çok kez ertelenebildiği için satır upsert edilir: aynı kimlikli satır varsa hedefi güncellenir ve
     * yeniden yayınlanmak üzere yayınlanmamış hale getirilir.
     */
    public void republish(MessageEnvelope envelope, InvoiceMessage payload, String exchange, String routingKey) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(routingKey, "routingKey");
        requireTransaction();
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_id, message_type, exchange, routing_key, payload)
                        VALUES (:id, :aggregateId, :messageType, :exchange, :routingKey, CAST(:payload AS jsonb))
                        ON CONFLICT (id) DO UPDATE
                            SET exchange = EXCLUDED.exchange, routing_key = EXCLUDED.routing_key,
                                payload = EXCLUDED.payload, created_at = now(), published_at = NULL, attempts = 0
                        """)
                .param("id", envelope.messageId())
                .param("aggregateId", envelope.correlationId())
                .param("messageType", MessageType.of(payload).typeName())
                .param("exchange", exchange)
                .param("routingKey", routingKey)
                .param("payload", converter.toJson(payload))
                .update();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Outbox'a yalnızca iş transaction'ı içinde yazılır");
        }
    }
}
