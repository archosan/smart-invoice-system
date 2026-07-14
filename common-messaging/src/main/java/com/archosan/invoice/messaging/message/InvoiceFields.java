package com.archosan.invoice.messaging.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** LLM'in çıkardığı fatura alanları (FR-E2). */
public record InvoiceFields(
        String supplierName,
        String supplierVkn,
        String invoiceNo,
        LocalDate invoiceDate,
        LocalDate dueDate,
        List<InvoiceLine> lines,
        BigDecimal subtotal,
        BigDecimal vatTotal,
        BigDecimal grandTotal,
        String currency) {

    public InvoiceFields {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
