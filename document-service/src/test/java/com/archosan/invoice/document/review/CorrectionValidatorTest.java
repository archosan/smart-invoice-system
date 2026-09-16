package com.archosan.invoice.document.review;

import com.archosan.invoice.document.DocumentProperties;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.RuleResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectionValidatorTest {

    private final CorrectionValidator validator = new CorrectionValidator(new DocumentProperties.Correction(
            new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.02")));

    @Test
    void consistentInvoicePassesEveryRule() {
        assertThat(validator.validate(fields(line("Rulman", "1.5", "100.00", 20), "150.00", "30.00", "180.00",
                "1234567890", LocalDate.of(2026, 10, 30))))
                .allMatch(RuleResult::passed)
                .hasSize(7);
    }

    @Test
    void amountsWithinToleranceStillPass() {
        assertThat(failed(fields(line("Rulman", "1", "100.00", 20), "100.02", "20.00", "120.01", "1234567890",
                LocalDate.of(2026, 10, 30)))).isEmpty();
    }

    @Test
    void arithmeticAndFormatErrorsAreReportedWithDetail() {
        List<RuleResult> failed = failed(fields(line("Rulman", "2", "100.00", 20), "150.00", "30.00", "200.00",
                "123", LocalDate.of(2026, 9, 1)));

        assertThat(failed).extracting(RuleResult::rule).containsExactlyInAnyOrder(
                "VKN_10_DIGITS", "LINES_SUM_EQUALS_SUBTOTAL", "SUBTOTAL_PLUS_VAT_EQUALS_TOTAL",
                "INVOICE_DATE_NOT_AFTER_DUE_DATE", "VAT_MATCHES_LINES");
        assertThat(failed).filteredOn(r -> r.rule().equals("LINES_SUM_EQUALS_SUBTOTAL")).singleElement()
                .extracting(RuleResult::detail)
                .isEqualTo("kalem toplamı 200.00 ≠ ara toplam 150.00 (fark 50.00, tolerans 0.02)");
    }

    @Test
    void missingFieldsAndBadLinesAreReported() {
        InvoiceFields blank = new InvoiceFields(" ", null, "FTR-1", LocalDate.of(2026, 9, 30), null,
                List.of(new InvoiceLine("", new BigDecimal("0"), new BigDecimal("-1"), 120)),
                null, null, null, "TRY");

        List<RuleResult> failed = failed(blank);

        assertThat(failed).filteredOn(r -> r.rule().equals("REQUIRED_FIELDS_PRESENT")).singleElement()
                .extracting(RuleResult::detail)
                .isEqualTo("eksik: tedarikçi adı, VKN, vade, ara toplam, KDV toplamı, genel toplam");
        assertThat(failed).filteredOn(r -> r.rule().equals("LINES_VALID")).singleElement()
                .extracting(RuleResult::detail)
                .isEqualTo("1. kalem: açıklama yok; 1. kalem: miktar pozitif olmalı; "
                        + "1. kalem: birim fiyat yok veya negatif; 1. kalem: KDV oranı 0–100 olmalı");
    }

    private List<RuleResult> failed(InvoiceFields fields) {
        return validator.validate(fields).stream().filter(r -> !r.passed()).toList();
    }

    private static InvoiceLine line(String description, String quantity, String unitPrice, int vatRate) {
        return new InvoiceLine(description, new BigDecimal(quantity), new BigDecimal(unitPrice), vatRate);
    }

    private static InvoiceFields fields(InvoiceLine line, String subtotal, String vat, String total, String vkn,
            LocalDate dueDate) {
        return new InvoiceFields("ACME A.Ş.", vkn, "FTR-1", LocalDate.of(2026, 9, 30), dueDate, List.of(line),
                new BigDecimal(subtotal), new BigDecimal(vat), new BigDecimal(total), "TRY");
    }
}
