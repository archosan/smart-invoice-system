package com.archosan.invoice.messaging.message;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Olay: compliance → document. {@code contractId} sözleşme bulunamadıysa null'dır. */
public record ComplianceCompleted(UUID documentId, Result result, UUID contractId, List<Finding> findings)
        implements InvoiceMessage {

    public ComplianceCompleted {
        Objects.requireNonNull(documentId, "documentId");
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public enum Result {
        COMPLIANT,
        NON_COMPLIANT,
        NO_CONTRACT,
        CONTRACT_CONFLICT
    }

    public enum Check {
        UNIT_PRICE,
        PAYMENT_TERM,
        CONTRACT_VALIDITY
    }

    /**
     * Tek bir karşılaştırma bulgusu. Değerler kontrol türüne göre fiyat, gün veya tarih olabildiği için
     * metin olarak taşınır.
     */
    public record Finding(
            Check check,
            String clauseNo,
            String quote,
            String invoiceValue,
            String contractValue,
            boolean reliable) {
    }
}
