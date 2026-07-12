package com.archosan.invoice.messaging.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Komut: document → compliance. */
public record CheckCompliance(
        UUID documentId,
        String supplierVkn,
        LocalDate invoiceDate,
        LocalDate dueDate,
        List<InvoiceLine> lines,
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal grandTotal,
        String currency) implements InvoiceMessage {

    public CheckCompliance {
        Objects.requireNonNull(documentId, "documentId");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
