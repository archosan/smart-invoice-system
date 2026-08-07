package com.archosan.invoice.document.query;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.RuleResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Kaydın tamamı: {@code documents} kolonları, {@code invoice_data}'dan alanlar ve kural sonuçları,
 * {@code compliance_results}'tan uyum sonucu. Henüz oluşmamış kısımlar {@code null}'dır. {@code duplicateOf} yalnızca
 * {@code DUPLICATE_SUSPECTED} kayıtta dolu: aynı faturanın reddedilmemiş diğer kayıtları (B-41).
 */
public record DocumentDetail(
        UUID id,
        DocumentStatus status,
        int version,
        String fileSha256,
        String supplierVkn,
        String invoiceNo,
        LocalDate invoiceDate,
        BigDecimal grandTotal,
        String currency,
        BigDecimal confidenceScore,
        String portalRefNo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        InvoiceFields fields,
        List<RuleResult> ruleResults,
        Compliance compliance,
        List<UUID> duplicateOf) {

    public record Compliance(ComplianceCompleted.Result result, List<ComplianceCompleted.Finding> findings) {
    }
}
