package com.archosan.invoice.rpa.portal;

import com.archosan.invoice.messaging.message.PostToPortal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Forma yazılacak metinler, portalın biçiminde (DECISIONS.md §4.5): tarih ISO, tutar nokta ondalıklı iki hane.
 * Eksik alan veya iki haneden fazla kuruş kalıcı hatadır; portala gidilmeden ayıklanır. Tutar yuvarlanmaz.
 */
public record PortalValues(
        String supplierVkn,
        String supplierName,
        String invoiceNo,
        String invoiceDate,
        String dueDate,
        String grandTotal,
        String vatTotal,
        String currency) {

    public static PortalValues of(PostToPortal command) {
        return new PortalValues(
                required("supplierVkn", command.supplierVkn()),
                required("supplierName", command.supplierName()),
                required("invoiceNo", command.invoiceNo()),
                required("invoiceDate", command.invoiceDate()).toString(),
                required("dueDate", command.dueDate()).toString(),
                amount("grandTotal", command.grandTotal()),
                amount("vatTotal", command.vatTotal()),
                required("currency", command.currency()));
    }

    private static <T> T required(String field, T value) {
        if (value == null || value instanceof String s && s.isBlank()) {
            throw new PortalRejectedException("Portal için eksik alan: " + field);
        }
        return value;
    }

    private static String amount(String field, BigDecimal value) {
        try {
            return required(field, value).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new PortalRejectedException("Portal tutarı en fazla iki ondalık kabul eder: " + field + "="
                    + value.toPlainString());
        }
    }
}
