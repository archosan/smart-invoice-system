package com.archosan.invoice.document.review;

import com.archosan.invoice.document.DocumentProperties;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.RuleResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Uzman düzeltmesinin yapısal kontrolü (B-40, FR-E3). extraction-service'teki kuralların LLM'den bağımsız olanları,
 * aynı kodlar ve toleranslarla: düzeltme insan girdisidir, güven skoru yoktur; ya hepsi geçer ya düzeltme reddedilir
 * (422). Ayrıştırma kuralı ({@code AMOUNTS_PARSED}) yoktur, değerler JSON'dan tipli gelir.
 */
@Component
public class CorrectionValidator {

    static final String REQUIRED_FIELDS_PRESENT = "REQUIRED_FIELDS_PRESENT";
    static final String VKN_10_DIGITS = "VKN_10_DIGITS";
    static final String LINES_VALID = "LINES_VALID";
    static final String LINES_SUM_EQUALS_SUBTOTAL = "LINES_SUM_EQUALS_SUBTOTAL";
    static final String SUBTOTAL_PLUS_VAT_EQUALS_TOTAL = "SUBTOTAL_PLUS_VAT_EQUALS_TOTAL";
    static final String INVOICE_DATE_NOT_AFTER_DUE_DATE = "INVOICE_DATE_NOT_AFTER_DUE_DATE";
    static final String VAT_MATCHES_LINES = "VAT_MATCHES_LINES";

    private final DocumentProperties.Correction config;

    @Autowired
    public CorrectionValidator(DocumentProperties properties) {
        this(properties.correction());
    }

    CorrectionValidator(DocumentProperties.Correction config) {
        this.config = config;
    }

    /** Bütün kuralların sonucu; düzeltme ancak hepsi geçerse kabul edilir. */
    public List<RuleResult> validate(InvoiceFields f) {
        return List.of(
                requiredFields(f),
                vkn(f),
                lines(f),
                linesSum(f),
                subtotalPlusVat(f),
                dateOrder(f),
                vatMatchesLines(f));
    }

    private static RuleResult requiredFields(InvoiceFields f) {
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
                ? pass(REQUIRED_FIELDS_PRESENT)
                : fail(REQUIRED_FIELDS_PRESENT, "eksik: " + String.join(", ", missing));
    }

    private static RuleResult vkn(InvoiceFields f) {
        String vkn = f.supplierVkn();
        if (vkn != null && vkn.matches("\\d{10}")) {
            return pass(VKN_10_DIGITS);
        }
        return fail(VKN_10_DIGITS, vkn == null ? "VKN yok" : "VKN " + vkn.length() + " hane: " + vkn);
    }

    /** Uzman girdisinde LLM kuralı olmayan bir kontrol: her kalem eksiksiz, miktar ve fiyat pozitif, oran 0–100. */
    private static RuleResult lines(InvoiceFields f) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < f.lines().size(); i++) {
            InvoiceLine line = f.lines().get(i);
            String label = (i + 1) + ". kalem: ";
            if (line.description() == null || line.description().isBlank()) {
                problems.add(label + "açıklama yok");
            }
            if (line.quantity() == null || line.quantity().signum() <= 0) {
                problems.add(label + "miktar pozitif olmalı");
            }
            if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
                problems.add(label + "birim fiyat yok veya negatif");
            }
            if (line.vatRate() < 0 || line.vatRate() > 100) {
                problems.add(label + "KDV oranı 0–100 olmalı");
            }
        }
        return problems.isEmpty() ? pass(LINES_VALID) : fail(LINES_VALID, String.join("; ", problems));
    }

    private RuleResult linesSum(InvoiceFields f) {
        BigDecimal sum = sumLines(f, CorrectionValidator::net);
        if (sum == null || f.subtotal() == null) {
            return fail(LINES_SUM_EQUALS_SUBTOTAL, "hesaplanamadı: kalem veya ara toplam eksik");
        }
        return compare(LINES_SUM_EQUALS_SUBTOTAL, "kalem toplamı", sum, "ara toplam", f.subtotal(),
                lineTolerance(f));
    }

    private RuleResult subtotalPlusVat(InvoiceFields f) {
        if (f.subtotal() == null || f.vatTotal() == null || f.grandTotal() == null) {
            return fail(SUBTOTAL_PLUS_VAT_EQUALS_TOTAL, "hesaplanamadı: toplamlardan biri eksik");
        }
        return compare(SUBTOTAL_PLUS_VAT_EQUALS_TOTAL, "ara toplam + KDV", f.subtotal().add(f.vatTotal()),
                "genel toplam", f.grandTotal(), config.totalTolerance());
    }

    private static RuleResult dateOrder(InvoiceFields f) {
        if (f.invoiceDate() == null || f.dueDate() == null) {
            return fail(INVOICE_DATE_NOT_AFTER_DUE_DATE, "karşılaştırılamadı: tarih eksik");
        }
        return f.invoiceDate().isAfter(f.dueDate())
                ? fail(INVOICE_DATE_NOT_AFTER_DUE_DATE,
                        "fatura tarihi " + f.invoiceDate() + " vadeden (" + f.dueDate() + ") sonra")
                : pass(INVOICE_DATE_NOT_AFTER_DUE_DATE);
    }

    private RuleResult vatMatchesLines(InvoiceFields f) {
        BigDecimal vat = sumLines(f, line -> net(line) == null ? null
                : net(line).multiply(BigDecimal.valueOf(line.vatRate())).divide(BigDecimal.valueOf(100), 2,
                        RoundingMode.HALF_UP));
        if (vat == null || f.vatTotal() == null) {
            return fail(VAT_MATCHES_LINES, "hesaplanamadı: kalem veya KDV toplamı eksik");
        }
        return compare(VAT_MATCHES_LINES, "kalemlerden KDV", vat, "KDV toplamı", f.vatTotal(), lineTolerance(f));
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
        return fail(rule, "%s %s ≠ %s %s (fark %s, tolerans %s)".formatted(leftLabel, left.toPlainString(),
                rightLabel, right.toPlainString(), difference.toPlainString(), tolerance.toPlainString()));
    }

    private static void check(List<String> missing, String label, Object value) {
        if (value == null || value instanceof String s && s.isBlank()) {
            missing.add(label);
        }
    }

    private static RuleResult pass(String rule) {
        return new RuleResult(rule, true, null);
    }

    private static RuleResult fail(String rule, String detail) {
        return new RuleResult(rule, false, detail);
    }
}
