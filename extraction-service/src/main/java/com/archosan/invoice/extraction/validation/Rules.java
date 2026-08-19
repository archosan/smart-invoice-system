package com.archosan.invoice.extraction.validation;

/** Kural kodları (FR-E3); {@code RuleResult.rule} değerleridir, yeni kural sözleşmeyi değiştirmez. */
public final class Rules {

    public static final String REQUIRED_FIELDS_PRESENT = "REQUIRED_FIELDS_PRESENT";
    public static final String VKN_10_DIGITS = "VKN_10_DIGITS";
    public static final String LINES_SUM_EQUALS_SUBTOTAL = "LINES_SUM_EQUALS_SUBTOTAL";
    public static final String SUBTOTAL_PLUS_VAT_EQUALS_TOTAL = "SUBTOTAL_PLUS_VAT_EQUALS_TOTAL";
    public static final String INVOICE_DATE_NOT_AFTER_DUE_DATE = "INVOICE_DATE_NOT_AFTER_DUE_DATE";
    public static final String AMOUNTS_PARSED = "AMOUNTS_PARSED";
    /** FR-E3'e ek (B-20 K3): oran başına KDV satırının toplam KDV sanılması gibi hataları yakalar. */
    public static final String VAT_MATCHES_LINES = "VAT_MATCHES_LINES";

    private Rules() {
    }
}
