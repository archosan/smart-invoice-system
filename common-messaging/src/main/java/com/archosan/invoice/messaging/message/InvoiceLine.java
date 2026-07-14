package com.archosan.invoice.messaging.message;

import java.math.BigDecimal;

/** Fatura kalemi; {@code vatRate} yüzde olarak tam sayıdır (20 = %20). */
public record InvoiceLine(String description, BigDecimal quantity, BigDecimal unitPrice, int vatRate) {
}
