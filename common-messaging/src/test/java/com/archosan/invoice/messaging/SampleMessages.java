package com.archosan.invoice.messaging;

import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.ExtractionCompleted;
import com.archosan.invoice.messaging.message.ExtractionFailed;
import com.archosan.invoice.messaging.message.IngestContract;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.message.RpaCompleted;
import com.archosan.invoice.messaging.message.RuleResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SampleMessages {

    public static final UUID DOCUMENT_ID = UUID.fromString("0b9a3c1e-6f0d-4d7e-9a51-2f8c6b1d4e70");

    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 9, 30);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 10, 30);
    private static final List<InvoiceLine> LINES =
            List.of(new InvoiceLine("Rulman 6204", new BigDecimal("1.5"), new BigDecimal("100.00"), 20));

    private SampleMessages() {
    }

    public static ExtractInvoice extractInvoice() {
        return new ExtractInvoice(DOCUMENT_ID, "file:///data/documents/ab12.pdf", "ab12");
    }

    public static ExtractionCompleted extractionCompleted() {
        InvoiceFields fields = new InvoiceFields("ACME A.Ş.", "1234567890", "FTR-2026-001", INVOICE_DATE, DUE_DATE,
                LINES, new BigDecimal("150.00"), new BigDecimal("30.00"), new BigDecimal("180.00"), "TRY");
        return new ExtractionCompleted(DOCUMENT_ID, fields, new BigDecimal("0.92"),
                List.of(new RuleResult("LINES_SUM_EQUALS_SUBTOTAL", true, null)), "qwen2.5:7b", "v1");
    }

    public static List<InvoiceMessage> all() {
        return List.of(
                extractInvoice(),
                extractionCompleted(),
                new ExtractionFailed(DOCUMENT_ID, ExtractionFailed.Reason.LLM_RETRIES_EXHAUSTED, "zaman aşımı", 3),
                new CheckCompliance(DOCUMENT_ID, "1234567890", INVOICE_DATE, DUE_DATE, LINES,
                        new BigDecimal("150.00"), new BigDecimal("30.00"), new BigDecimal("180.00"), "TRY"),
                new ComplianceCompleted(DOCUMENT_ID, ComplianceCompleted.Result.NON_COMPLIANT, UUID.randomUUID(),
                        List.of(new ComplianceCompleted.Finding(ComplianceCompleted.Check.UNIT_PRICE, "7.2",
                                "Birim fiyat 95,00 TL'dir.", "100.00", "95.00", true))),
                new PostToPortal(DOCUMENT_ID, "1234567890", "ACME A.Ş.", "FTR-2026-001", INVOICE_DATE, DUE_DATE,
                        new BigDecimal("180.00"), new BigDecimal("30.00"), "TRY"),
                new RpaCompleted(DOCUMENT_ID, "4711", false),
                new IngestContract(UUID.randomUUID(), "file:///data/contracts/cd34.pdf"));
    }
}
