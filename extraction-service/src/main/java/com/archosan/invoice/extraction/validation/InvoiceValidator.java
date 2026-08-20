package com.archosan.invoice.extraction.validation;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.extraction.llm.LlmInvoice;
import com.archosan.invoice.extraction.parse.TurkishDates;
import com.archosan.invoice.extraction.parse.TurkishNumbers;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.RuleResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Çıkarılan alanları doğrular (FR-E3, B-20). Girdi LLM'in ham çıktısı (basılı değerler) ve ayrıştırılmış alanlardır;
 * böylece "LLM bir şey yazdı ama okunamadı" durumu {@code null}'a karışmadan yakalanır. Her kural her zaman bir sonuç
 * üretir; açıklamalar uzmanın okuyacağı Türkçe metinlerdir. Eksik değer yüzünden hesaplanamayan kural kalır.
 */
@Component
public class InvoiceValidator {

    private final ExtractionProperties.Validation config;

    public InvoiceValidator(ExtractionProperties properties) {
        this.config = properties.validation();
    }

    public List<RuleResult> validate(LlmInvoice raw, InvoiceFields fields) {
        return List.of(
                requiredFields(fields),
                vkn(fields),
                linesSum(fields),
                subtotalPlusVat(fields),
                dateOrder(fields),
                amountsParsed(raw, fields),
                vatMatchesLines(fields));
    }

    private RuleResult requiredFields(InvoiceFields f) {
        List<String> missing = new ArrayList<>();
        check(missing, "tedarikçi adı", f.supplierName());
        check(missing, "VKN", f.supplierVkn());
        check(missing, "fatura no", f.invoiceNo());
        check(missing, "fatura tarihi", f.invoiceDate());
        check(missing, "vade", f.dueDate());
        if (f.lines().isEmpty()) {
            missing.add("kalemler");
        }
        check(missing, "ara toplam", f.subtotal());
        check(missing, "KDV toplamı", f.vatTotal());
        check(missing, "genel toplam", f.grandTotal());
        check(missing, "para birimi", f.currency());
        return missing.isEmpty()
                ? pass(Rules.REQUIRED_FIELDS_PRESENT)
                : fail(Rules.REQUIRED_FIELDS_PRESENT, "eksik: " + String.join(", ", missing));
    }

    private RuleResult vkn(InvoiceFields f) {
        String vkn = f.supplierVkn();
        if (vkn != null && vkn.matches("\\d{10}")) {
            return pass(Rules.VKN_10_DIGITS);
        }
        return fail(Rules.VKN_10_DIGITS, vkn == null ? "VKN yok" : "VKN " + vkn.length() + " hane: " + vkn);
    }

    private RuleResult linesSum(InvoiceFields f) {
        BigDecimal sum = sumLines(f, InvoiceValidator::net);
        if (sum == null || f.subtotal() == null) {
            return fail(Rules.LINES_SUM_EQUALS_SUBTOTAL, "hesaplanamadı: kalem veya ara toplam eksik");
        }
        return compare(Rules.LINES_SUM_EQUALS_SUBTOTAL, "kalem toplamı", sum, "ara toplam", f.subtotal(),
                lineTolerance(f));
    }

    private RuleResult subtotalPlusVat(InvoiceFields f) {
        if (f.subtotal() == null || f.vatTotal() == null || f.grandTotal() == null) {
            return fail(Rules.SUBTOTAL_PLUS_VAT_EQUALS_TOTAL, "hesaplanamadı: toplamlardan biri eksik");
        }
        return compare(Rules.SUBTOTAL_PLUS_VAT_EQUALS_TOTAL, "ara toplam + KDV", f.subtotal().add(f.vatTotal()),
                "genel toplam", f.grandTotal(), config.totalTolerance());
    }

    private RuleResult dateOrder(InvoiceFields f) {
        if (f.invoiceDate() == null || f.dueDate() == null) {
            return fail(Rules.INVOICE_DATE_NOT_AFTER_DUE_DATE, "karşılaştırılamadı: tarih eksik");
        }
        return f.invoiceDate().isAfter(f.dueDate())
                ? fail(Rules.INVOICE_DATE_NOT_AFTER_DUE_DATE,
                        "fatura tarihi " + f.invoiceDate() + " vadeden (" + f.dueDate() + ") sonra")
                : pass(Rules.INVOICE_DATE_NOT_AFTER_DUE_DATE);
    }

    /** LLM'in dolu döndürdüğü ama Türkçe ayrıştırıcının okuyamadığı değerler (NFR-14). */
    private RuleResult amountsParsed(LlmInvoice raw, InvoiceFields f) {
        List<String> unreadable = new ArrayList<>();
        unreadableAmount(unreadable, "ara toplam", raw.subtotal());
        unreadableAmount(unreadable, "KDV toplamı", raw.vatTotal());
        unreadableAmount(unreadable, "genel toplam", raw.grandTotal());
        unreadableDate(unreadable, "fatura tarihi", raw.invoiceDate());
        unreadableDate(unreadable, "vade", raw.dueDate());
        List<LlmInvoice.Line> lines = raw.lines() == null ? List.of() : raw.lines();
        for (int i = 0; i < lines.size(); i++) {
            LlmInvoice.Line line = lines.get(i);
            String label = (i + 1) + ". kalem ";
            if (line.quantity() != null && !line.quantity().isBlank()
                    && TurkishNumbers.parseQuantity(line.quantity()) == null) {
                unreadable.add(label + "miktar \"" + line.quantity() + "\"");
            }
            unreadableAmount(unreadable, label + "birim fiyat", line.unitPrice());
            if (line.vatRate() != null && !line.vatRate().isBlank() && TurkishNumbers.parseRate(line.vatRate()) == null) {
                unreadable.add(label + "KDV oranı \"" + line.vatRate() + "\"");
            }
        }
        return unreadable.isEmpty()
                ? pass(Rules.AMOUNTS_PARSED)
                : fail(Rules.AMOUNTS_PARSED, "okunamadı: " + String.join("; ", unreadable));
    }

    private RuleResult vatMatchesLines(InvoiceFields f) {
        BigDecimal vat = sumLines(f, line -> net(line) == null ? null
                : net(line).multiply(BigDecimal.valueOf(line.vatRate())).divide(BigDecimal.valueOf(100), 2,
                        RoundingMode.HALF_UP));
        if (vat == null || f.vatTotal() == null) {
            return fail(Rules.VAT_MATCHES_LINES, "hesaplanamadı: kalem veya KDV toplamı eksik");
        }
        return compare(Rules.VAT_MATCHES_LINES, "kalemlerden KDV", vat, "KDV toplamı", f.vatTotal(),
                lineTolerance(f));
    }

    private BigDecimal lineTolerance(InvoiceFields f) {
        return config.minimumTolerance().max(config.perLineTolerance().multiply(BigDecimal.valueOf(f.lines().size())));
    }

    private static BigDecimal sumLines(InvoiceFields f, Function<InvoiceLine, BigDecimal> value) {
        if (f.lines().isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (InvoiceLine line : f.lines()) {
            BigDecimal v = value.apply(line);
            if (v == null) {
                return null;
            }
            sum = sum.add(v);
        }
        return sum;
    }

    private static BigDecimal net(InvoiceLine line) {
        return line.quantity() == null || line.unitPrice() == null
                ? null
                : line.quantity().multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_UP);
    }

    private static RuleResult compare(String rule, String leftLabel, BigDecimal left, String rightLabel,
            BigDecimal right, BigDecimal tolerance) {
        BigDecimal difference = left.subtract(right).abs();
        if (difference.compareTo(tolerance) <= 0) {
            return pass(rule);
        }
        return fail(rule, "%s %s ≠ %s %s (fark %s, tolerans %s)".formatted(leftLabel, TurkishNumbers.format(left),
                rightLabel, TurkishNumbers.format(right), TurkishNumbers.format(difference),
                TurkishNumbers.format(tolerance)));
    }

    private static void check(List<String> missing, String label, Object value) {
        if (value == null) {
            missing.add(label);
        }
    }

    private static void unreadableAmount(List<String> unreadable, String label, String printed) {
        if (printed != null && !printed.isBlank() && TurkishNumbers.parse(printed) == null) {
            unreadable.add(label + " \"" + printed + "\"");
        }
    }

    private static void unreadableDate(List<String> unreadable, String label, String printed) {
        if (printed != null && !printed.isBlank() && TurkishDates.parse(printed) == null) {
            unreadable.add(label + " \"" + printed + "\"");
        }
    }

    private static RuleResult pass(String rule) {
        return new RuleResult(rule, true, null);
    }

    private static RuleResult fail(String rule, String detail) {
        return new RuleResult(rule, false, Objects.requireNonNull(detail));
    }
}
