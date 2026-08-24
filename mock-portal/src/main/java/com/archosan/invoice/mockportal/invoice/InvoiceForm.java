package com.archosan.invoice.mockportal.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Fatura giriş formunun ham değerleri. Hatalı girişte form aynı değerlerle yeniden gösterilsin diye alanlar metindir;
 * doğrulama {@link #validate()} ile, eski bir portal gibi sunucu tarafında yapılır.
 *
 * <p>Biçimler: VKN 10 hane, tarihler ISO ({@code 2026-09-30}, tarayıcının {@code type=date} alanı), tutarlar nokta
 * ondalıklı ({@code 5100.00}).
 */
public record InvoiceForm(
        String supplierVkn,
        String supplierName,
        String invoiceNo,
        String invoiceDate,
        String dueDate,
        String grandTotal,
        String vatTotal,
        String currency) {

    public static final Set<String> CURRENCIES = Set.of("TRY", "USD", "EUR");

    public static InvoiceForm empty() {
        return new InvoiceForm("", "", "", "", "", "", "", "TRY");
    }

    /** Doğrulanmış ve çevrilmiş değerler. */
    public record Valid(
            String supplierVkn,
            String supplierName,
            String invoiceNo,
            LocalDate invoiceDate,
            LocalDate dueDate,
            BigDecimal grandTotal,
            BigDecimal vatTotal,
            String currency) {
    }

    /** Ya doğrulanmış değerler ya da alan adı → hata mesajı. */
    public record Result(Valid valid, Map<String, String> errors) {

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public Result validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        String vkn = strip(supplierVkn);
        if (!vkn.matches("\\d{10}")) {
            errors.put("supplierVkn", "VKN 10 haneli olmalı");
        }
        String name = required("supplierName", supplierName, "Tedarikçi adı zorunlu", errors);
        String no = required("invoiceNo", invoiceNo, "Fatura no zorunlu", errors);
        LocalDate issued = date("invoiceDate", invoiceDate, errors);
        LocalDate due = date("dueDate", dueDate, errors);
        BigDecimal total = amount("grandTotal", grandTotal, errors);
        BigDecimal vat = amount("vatTotal", vatTotal, errors);
        String cur = strip(currency);
        if (!CURRENCIES.contains(cur)) {
            errors.put("currency", "Geçersiz para birimi");
        }
        if (!errors.isEmpty()) {
            return new Result(null, Map.copyOf(errors));
        }
        return new Result(new Valid(vkn, name, no, issued, due, total, vat, cur), Map.of());
    }

    private static String required(String field, String value, String message, Map<String, String> errors) {
        String v = strip(value);
        if (v.isEmpty()) {
            errors.put(field, message);
        }
        return v;
    }

    private static LocalDate date(String field, String value, Map<String, String> errors) {
        try {
            return LocalDate.parse(strip(value));
        } catch (DateTimeParseException e) {
            errors.put(field, "Tarih YYYY-AA-GG biçiminde olmalı");
            return null;
        }
    }

    private static BigDecimal amount(String field, String value, Map<String, String> errors) {
        String v = strip(value);
        if (!v.matches("\\d+(\\.\\d{1,2})?")) {
            errors.put(field, "Tutar nokta ondalıklı olmalı (örn. 5100.00)");
            return null;
        }
        return new BigDecimal(v);
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
