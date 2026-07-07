package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.message.InvoiceMessage;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DLQ'dan gelen mesaj, ham haliyle. Zehirli olduğu için DLQ'ya düşmüş olabileceğinden çözümlenmesi şart değildir:
 * {@code envelope} ve {@code payload} yalnızca mesaj çözülebildiyse doludur.
 *
 * @param deadLetterQueue  mesajın okunduğu DLQ
 * @param deathQueue       mesajı DLQ'ya düşüren ölümün kuyruğu (asıl kuyruk); {@code x-last-death-queue}, yoksa
 *                         {@code x-first-death-queue}. İlk ölüm yetmez: bekleme odasından geçen mesajın ilk ölümü
 *                         oradaki TTL'dir ({@code expired}, B-37)
 * @param deathReason      o ölümün nedeni: {@code rejected}, {@code delivery_limit} …
 * @param xDeath           {@code x-death} başlığı
 */
public record DeadLetter(
        String deadLetterQueue,
        String messageId,
        String type,
        String correlationId,
        byte[] body,
        String deathQueue,
        String deathReason,
        List<Map<String, ?>> xDeath,
        MessageEnvelope envelope,
        InvoiceMessage payload) {

    public Optional<UUID> messageIdAsUuid() {
        return parseUuid(messageId);
    }

    public Optional<UUID> correlationIdAsUuid() {
        return parseUuid(correlationId);
    }

    public String bodyAsText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
