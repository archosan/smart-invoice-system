package com.archosan.invoice.document.query;

import com.archosan.invoice.document.DocumentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durum geçmişinin bir satırı (B-42, US-12): kim, ne zaman, hangi durumdan hangisine. {@code from} ilk satırda (yükleme)
 * {@code null}; {@code messageId} yalnız mesajla tetiklenen geçişte dolu.
 */
public record HistoryEntry(
        DocumentStatus from,
        DocumentStatus to,
        String event,
        String actor,
        String reason,
        UUID messageId,
        OffsetDateTime at) {
}
