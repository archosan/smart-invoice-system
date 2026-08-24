package com.archosan.invoice.mockportal.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Portala girilmiş bir fatura; {@code refNo} portalın verdiği kayıt numarasıdır (FR-P2). */
public record PortalInvoice(
        String refNo,
        String supplierVkn,
        String supplierName,
        String invoiceNo,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal grandTotal,
        BigDecimal vatTotal,
        String currency,
        Instant createdAt) {
}
