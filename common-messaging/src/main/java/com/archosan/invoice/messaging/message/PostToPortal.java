package com.archosan.invoice.messaging.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Komut: document → rpa. */
public record PostToPortal(
        UUID documentId,
        String supplierVkn,
        String supplierName,
        String invoiceNo,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal grandTotal,
        BigDecimal vatTotal,
        String currency) implements InvoiceMessage {

    public PostToPortal {
        Objects.requireNonNull(documentId, "documentId");
    }
}
