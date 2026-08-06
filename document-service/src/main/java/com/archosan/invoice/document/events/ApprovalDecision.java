package com.archosan.invoice.document.events;

import com.archosan.invoice.messaging.message.InvoiceFields;

import java.math.BigDecimal;

/**
 * Uyumlu faturanın onaya gidip gitmeyeceği (FR-D11, B-39). Genel toplam tutar eşiğini aşarsa ({@code >}) onay
 * gerekir. Eşik TL'dir ve kur çevrimi yoktur: TRY dışı para birimi her zaman onaya gider. Alanlar eksikse karar
 * insana kalır.
 */
record ApprovalDecision(boolean required, String reason) {

    static final String HOME_CURRENCY = "TRY";

    static ApprovalDecision decide(InvoiceFields fields, BigDecimal threshold) {
        if (fields == null || fields.grandTotal() == null || fields.currency() == null) {
            return new ApprovalDecision(true, "tutar veya para birimi yok");
        }
        if (!HOME_CURRENCY.equals(fields.currency())) {
            return new ApprovalDecision(true, "para birimi " + fields.currency() + " ≠ " + HOME_CURRENCY);
        }
        String comparison = fields.grandTotal().toPlainString() + " %s eşik " + threshold.toPlainString();
        if (fields.grandTotal().compareTo(threshold) > 0) {
            return new ApprovalDecision(true, "tutar " + comparison.formatted(">"));
        }
        return new ApprovalDecision(false, "tutar " + comparison.formatted("≤"));
    }
}
