package com.archosan.invoice.messaging.message;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Olay: extraction → document. Güven eşiği kararı document-service'tedir (ADR-13). */
public record ExtractionCompleted(
        UUID documentId,
        InvoiceFields fields,
        BigDecimal confidenceScore,
        List<RuleResult> ruleResults,
        String model,
        String promptVersion) implements InvoiceMessage {

    public ExtractionCompleted {
        Objects.requireNonNull(documentId, "documentId");
        ruleResults = ruleResults == null ? List.of() : List.copyOf(ruleResults);
    }
}
