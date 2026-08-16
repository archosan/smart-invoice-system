package com.archosan.invoice.extraction.llm;

import java.util.List;

/**
 * LLM'in ürettiği ham çıktı (FR-E2). Bütün değerler faturada <b>basıldığı gibi</b> string'dir (örn.
 * {@code "1.234,56 TL"}, {@code "18/09/2026"}); çeviriyi deterministik ayrıştırıcılar yapar (B-18 K3).
 */
public record LlmInvoice(
        String supplierName,
        String supplierVkn,
        String invoiceNo,
        String invoiceDate,
        String dueDate,
        List<Line> lines,
        String subtotal,
        String vatTotal,
        String grandTotal,
        String currency) {

    public record Line(String description, String quantity, String unitPrice, String vatRate) {
    }
}
