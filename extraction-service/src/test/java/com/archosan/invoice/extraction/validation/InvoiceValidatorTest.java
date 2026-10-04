package com.archosan.invoice.extraction.validation;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.extraction.llm.LlmInvoice;
import com.archosan.invoice.extraction.testdata.PrintedInvoices;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.RuleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceValidatorTest {

    private static final ExtractionProperties PROPERTIES = ExtractionProperties.of(Path.of("/x"), 20,
            new ExtractionProperties.Llm("m", Duration.ofSeconds(1), 2, 1, Duration.ofSeconds(1), 3,
                    Duration.ofSeconds(30), 8192, 4096, "v2"));

    private final InvoiceValidator validator = new InvoiceValidator(PROPERTIES);

    static List<SyntheticInvoice> invoices() {
        return SyntheticInvoices.all().stream()
                .filter(i -> i.expectedOutcome() != SyntheticInvoice.ExpectedOutcome.NO_TEXT).toList();
    }

    @ParameterizedTest
    @MethodSource("invoices")
    void syntheticSetPassesExceptTheBrokenInvoice(SyntheticInvoice invoice) {
        LlmInvoice raw = PrintedInvoices.asPrinted(invoice);

        Map<String, RuleResult> results = byRule(validator.validate(raw, invoice.fields()));

        assertThat(results).hasSize(7);
        if (invoice.expectedOutcome() == SyntheticInvoice.ExpectedOutcome.POSTED) {
            assertThat(results.values()).allSatisfy(r -> assertThat(r.passed()).as(r.rule() + ": " + r.detail()).isTrue());
        } else {
            assertThat(failed(results)).containsExactlyInAnyOrder(Rules.LINES_SUM_EQUALS_SUBTOTAL, Rules.VAT_MATCHES_LINES);
            assertThat(results.get(Rules.LINES_SUM_EQUALS_SUBTOTAL).detail())
                    .isEqualTo("kalem toplamı 15.100,00 ≠ ara toplam 15.400,00 (fark 300,00, tolerans 0,02)");
        }
    }

    @Test
    void reportsEveryMissingRequiredField() {
        InvoiceFields empty = new InvoiceFields(null, null, null, null, null, List.of(), null, null, null, null);

        RuleResult result = byRule(validator.validate(rawOf(empty), empty)).get(Rules.REQUIRED_FIELDS_PRESENT);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("eksik: tedarikçi adı, VKN, fatura no, fatura tarihi, vade, kalemler, "
                + "ara toplam, KDV toplamı, genel toplam, para birimi");
    }

    @ParameterizedTest
    @CsvSource({"1234567890, true", "123456789, false", "12345678901, false"})
    void vknMustHaveTenDigits(String vkn, boolean passes) {
        InvoiceFields fields = valid().withVkn(vkn);

        assertThat(byRule(validator.validate(rawOf(fields), fields)).get(Rules.VKN_10_DIGITS).passed())
                .isEqualTo(passes);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 0.02, true", "1, 0.03, false",      // tek kalem: tolerans alt sınırı 0,02
            "5, 0.05, true", "5, 0.06, false"})     // 5 kalem: 5 × 0,01
    void linesSumToleranceScalesWithLineCount(int lineCount, String difference, boolean passes) {
        List<InvoiceLine> lines = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            lines.add(new InvoiceLine("k" + i, BigDecimal.ONE, new BigDecimal("10.00"), 20));
        }
        BigDecimal subtotal = new BigDecimal("10.00").multiply(BigDecimal.valueOf(lineCount)).add(new BigDecimal(difference));
        InvoiceFields fields = valid().withLines(lines, subtotal);

        assertThat(byRule(validator.validate(rawOf(fields), fields)).get(Rules.LINES_SUM_EQUALS_SUBTOTAL).passed())
                .isEqualTo(passes);
    }

    @Test
    void subtotalPlusVatUsesFixedTolerance() {
        InvoiceFields off = valid().withGrandTotal(new BigDecimal("120.03"));
        InvoiceFields within = valid().withGrandTotal(new BigDecimal("120.02"));

        assertThat(byRule(validator.validate(rawOf(off), off)).get(Rules.SUBTOTAL_PLUS_VAT_EQUALS_TOTAL).passed()).isFalse();
        assertThat(byRule(validator.validate(rawOf(within), within)).get(Rules.SUBTOTAL_PLUS_VAT_EQUALS_TOTAL).passed())
                .isTrue();
    }

    @Test
    void invoiceDateAfterDueDateFails() {
        InvoiceFields fields = valid().withDates(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1));

        RuleResult result = byRule(validator.validate(rawOf(fields), fields)).get(Rules.INVOICE_DATE_NOT_AFTER_DUE_DATE);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("2026-10-02", "2026-10-01");
    }

    @Test
    void unreadablePrintedValuesAreReportedNotSilentlyNull() {
        InvoiceFields fields = valid().build();
        LlmInvoice raw = new LlmInvoice("ACME", "1234567890", "F-1", "1 Eylül 2026", "01.10.2026",
                List.of(new LlmInvoice.Line("Kalem", "bir", "100,00 TL", "yirmi")),
                "100,00 TL", "20,00 TL", "yüz yirmi TL", "TL");

        RuleResult result = byRule(validator.validate(raw, fields)).get(Rules.AMOUNTS_PARSED);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).contains("genel toplam \"yüz yirmi TL\"", "fatura tarihi \"1 Eylül 2026\"",
                "1. kalem miktar \"bir\"", "1. kalem KDV oranı \"yirmi\"");
    }

    @Test
    void vatPerRateLineMistakenForTotalIsCaught() {
        // 2. faturadaki gibi: LLM toplam KDV yerine yalnızca %20 satırını alırsa ara toplam + KDV de tutmaz,
        // ama genel toplamı da yanlış okursa yalnızca VAT_MATCHES_LINES yakalar.
        InvoiceFields fields = valid().withVat(new BigDecimal("10.00"), new BigDecimal("110.00"));

        Map<String, RuleResult> results = byRule(validator.validate(rawOf(fields), fields));

        assertThat(results.get(Rules.SUBTOTAL_PLUS_VAT_EQUALS_TOTAL).passed()).isTrue();
        assertThat(results.get(Rules.VAT_MATCHES_LINES).passed()).isFalse();
    }

    @Test
    void missingValuesMakeArithmeticRulesFailWithExplanation() {
        InvoiceFields fields = valid().withLines(List.of(new InvoiceLine("k", null, new BigDecimal("10"), 20)),
                new BigDecimal("100.00"));

        Map<String, RuleResult> results = byRule(validator.validate(rawOf(fields), fields));

        assertThat(results.get(Rules.LINES_SUM_EQUALS_SUBTOTAL).detail()).startsWith("hesaplanamadı");
        assertThat(results.get(Rules.VAT_MATCHES_LINES).detail()).startsWith("hesaplanamadı");
    }

    private static Map<String, RuleResult> byRule(List<RuleResult> results) {
        return results.stream().collect(Collectors.toMap(RuleResult::rule, r -> r));
    }

    private static List<String> failed(Map<String, RuleResult> results) {
        return results.values().stream().filter(r -> !r.passed()).map(RuleResult::rule).toList();
    }

    /** Basılı değerler ayrıştırılabilir biçimde (yalnızca ayrıştırma kuralı için önemli). */
    private static LlmInvoice rawOf(InvoiceFields f) {
        return new LlmInvoice(f.supplierName(), f.supplierVkn(), f.invoiceNo(), str(f.invoiceDate()),
                str(f.dueDate()), f.lines().stream().map(l -> new LlmInvoice.Line(l.description(), str(l.quantity()),
                str(l.unitPrice()), String.valueOf(l.vatRate()))).toList(), str(f.subtotal()), str(f.vatTotal()),
                str(f.grandTotal()), f.currency());
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Fields valid() {
        return new Fields();
    }

    /** Geçerli bir temel fatura: 1 × 100,00, %20. */
    private static final class Fields {

        private String vkn = "1234567890";
        private List<InvoiceLine> lines = List.of(new InvoiceLine("Kalem", BigDecimal.ONE, new BigDecimal("100.00"), 20));
        private BigDecimal subtotal = new BigDecimal("100.00");
        private BigDecimal vat = new BigDecimal("20.00");
        private BigDecimal grand = new BigDecimal("120.00");
        private LocalDate invoiceDate = LocalDate.of(2026, 9, 1);
        private LocalDate dueDate = LocalDate.of(2026, 10, 1);

        InvoiceFields withVkn(String value) {
            vkn = value;
            return build();
        }

        InvoiceFields withLines(List<InvoiceLine> value, BigDecimal newSubtotal) {
            lines = value;
            subtotal = newSubtotal;
            return build();
        }

        InvoiceFields withGrandTotal(BigDecimal value) {
            grand = value;
            return build();
        }

        InvoiceFields withVat(BigDecimal newVat, BigDecimal newGrand) {
            vat = newVat;
            grand = newGrand;
            return build();
        }

        InvoiceFields withDates(LocalDate invoice, LocalDate due) {
            invoiceDate = invoice;
            dueDate = due;
            return build();
        }

        InvoiceFields build() {
            return new InvoiceFields("ACME", vkn, "F-1", invoiceDate, dueDate, lines, subtotal, vat, grand, "TRY");
        }
    }
}
