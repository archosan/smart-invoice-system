package com.archosan.invoice.document.query;

import com.archosan.invoice.document.DocumentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Liste öğesi. */
public record DocumentSummary(
        UUID id,
        DocumentStatus status,
        String supplierVkn,
        String invoiceNo,
        LocalDate invoiceDate,
        BigDecimal grandTotal,
        String currency,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
