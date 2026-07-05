package com.archosan.invoice.messaging;

import java.util.Objects;
import java.util.UUID;

/**
 * Her mesajın AMQP özelliklerinde taşınan zarf (DECISIONS.md §5).
 *
 * @param messageId     outbox satırının id'si; inbox anahtarı
 * @param correlationId documentId (sözleşme mesajlarında contractId); MDC'ye konur
 * @param type          mesaj tipi; AMQP {@code type} alanına adı yazılır
 * @param schemaVersion {@code x-schema-version} başlığı
 */
public record MessageEnvelope(UUID messageId, UUID correlationId, MessageType type, int schemaVersion) {

    public MessageEnvelope {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(type, "type");
    }

    /** Tipin güncel şema sürümüyle bir zarf üretir. */
    public static MessageEnvelope of(UUID messageId, UUID correlationId, MessageType type) {
        return new MessageEnvelope(messageId, correlationId, type, type.schemaVersion());
    }
}
