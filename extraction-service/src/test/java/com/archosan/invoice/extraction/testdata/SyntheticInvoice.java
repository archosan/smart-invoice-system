package com.archosan.invoice.extraction.testdata;

import com.archosan.invoice.messaging.message.InvoiceFields;

/**
 * Sentetik fatura senaryosu (B-16). {@code fields} faturanın üzerinde <b>basılı olan</b> değerlerdir; LLM'den
 * beklenen çıktı budur. Toplamları tutmayan faturada da basılı (tutarsız) değerler yazılıdır.
 *
 * @param file            PDF dosya adı
 * @param scenario        neyi sınadığı
 * @param expectedOutcome uçtan uca beklenen sonuç
 * @param fields          basılı değerler
 * @param layout          çizim seçenekleri
 * @param note            kalibrasyon (B-29) için açıklama
 */
public record SyntheticInvoice(
        String file,
        String scenario,
        ExpectedOutcome expectedOutcome,
        InvoiceFields fields,
        Layout layout,
        String note) {

    SyntheticInvoice withOutcome(ExpectedOutcome outcome) {
        return new SyntheticInvoice(file, scenario, outcome, fields, layout, note);
    }

    public enum ExpectedOutcome {
        /** Kurallar geçer, güven yüksek; insan müdahalesiz POSTED olmalı. */
        POSTED,
        /** Kural ihlali; güven düşer, NEEDS_REVIEW olmalı. */
        NEEDS_REVIEW,
        /** Metin katmanı yok; ExtractionFailed(NO_TEXT) → NEEDS_REVIEW. */
        NO_TEXT
    }

    public enum CurrencyStyle {
        /** {@code 1.234,56 TL} */
        SUFFIX_TL,
        /** Birim fiyatlar {@code ₺1.234,56}, toplamlar {@code 1.234,56 TL} */
        MIXED_LIRA_SIGN
    }

    /**
     * @param datePattern      {@code dd.MM.yyyy} veya {@code dd/MM/yyyy}
     * @param eArchiveNoise    ETTN, vergi dairesi, IBAN, MERSİS gibi ayıklanması gereken alanlar
     * @param vatBreakdown     KDV'yi oran başına ayrı satırlarda göster
     * @param imageOnly        sayfayı resim olarak çiz (metin katmanı yok)
     */
    public record Layout(
            String title,
            String datePattern,
            CurrencyStyle currencyStyle,
            boolean eArchiveNoise,
            boolean vatBreakdown,
            boolean imageOnly) {

        static Layout plain() {
            return new Layout("FATURA", "dd.MM.yyyy", CurrencyStyle.SUFFIX_TL, false, false, false);
        }

        Layout withTitle(String value) {
            return new Layout(value, datePattern, currencyStyle, eArchiveNoise, vatBreakdown, imageOnly);
        }

        Layout withDatePattern(String value) {
            return new Layout(title, value, currencyStyle, eArchiveNoise, vatBreakdown, imageOnly);
        }

        Layout withCurrencyStyle(CurrencyStyle value) {
            return new Layout(title, datePattern, value, eArchiveNoise, vatBreakdown, imageOnly);
        }

        Layout withEArchiveNoise() {
            return new Layout(title, datePattern, currencyStyle, true, vatBreakdown, imageOnly);
        }

        Layout withVatBreakdown() {
            return new Layout(title, datePattern, currencyStyle, eArchiveNoise, true, imageOnly);
        }

        Layout imageOnlyPage() {
            return new Layout(title, datePattern, currencyStyle, eArchiveNoise, vatBreakdown, true);
        }
    }
}
