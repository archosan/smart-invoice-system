package com.archosan.invoice.document.events;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.messaging.message.InvoiceFields;

import java.math.BigDecimal;

/**
 * {@code ExtractionCompleted} sonrası güven kararı (öncelik kuralının ilk adımı, DECISIONS.md §4.1; eşik ADR-13): skor
 * eşiğin altındaysa {@code NEEDS_REVIEW}, değilse {@code VALIDATED} adayı; mükerrer kontrolü ({@link DuplicateCheck})
 * bundan sonra gelir. Skor ya da alanlar yoksa karar insana kalır.
 */
record ReviewDecision(DocumentStatus target, String reason) {

    static ReviewDecision decide(InvoiceFields fields, BigDecimal confidenceScore, BigDecimal threshold) {
        if (fields == null) {
            return new ReviewDecision(DocumentStatus.NEEDS_REVIEW, "alanlar yok");
        }
        if (confidenceScore == null) {
            return new ReviewDecision(DocumentStatus.NEEDS_REVIEW, "güven skoru yok");
        }
        if (confidenceScore.compareTo(threshold) < 0) {
            return new ReviewDecision(DocumentStatus.NEEDS_REVIEW,
                    "güven " + confidenceScore.toPlainString() + " < eşik " + threshold.toPlainString());
        }
        return new ReviewDecision(DocumentStatus.VALIDATED,
                "güven " + confidenceScore.toPlainString() + " ≥ eşik " + threshold.toPlainString());
    }
}
