package com.archosan.invoice.compliance.check;

import com.archosan.invoice.compliance.contract.ContractRepository.RetrievedChunk;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Grounding (FR-C2 adım 5, ADR-09): LLM'in alıntısı getirilen chunk'larda, boşluklar normalize edilerek, harfi harfine
 * aranır; ayrıca değer alıntının içinde yazılı olmalıdır (model doğru alıntıya yanlış sayı eklemesin). İkisinden biri
 * tutmazsa bulgu güvenilmezdir ve fatura insan onayına gider.
 */
final class Grounding {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MIN_QUOTE_CHARS = 8;

    private Grounding() {
    }

    /** Alıntı en az birkaç karakterse ve chunk'lardan birinde harfi harfine geçiyorsa {@code true}. */
    static boolean isQuoted(String quote, List<RetrievedChunk> chunks) {
        if (quote == null || normalize(quote).length() < MIN_QUOTE_CHARS) {
            return false;
        }
        String needle = normalize(quote);
        return chunks.stream().anyMatch(c -> normalize(c.content()).contains(needle));
    }

    /** Fiyat: {@code 1840.00} → alıntıda {@code 1.840,00}, {@code 1840,00} ya da {@code 1840.00} olarak geçmeli. */
    static Optional<BigDecimal> price(BigDecimal price, String quote) {
        if (price == null || price.signum() < 0 || quote == null) {
            return Optional.empty();
        }
        String text = normalize(quote);
        boolean written = text.contains(turkish(price, true)) || text.contains(turkish(price, false))
                || text.contains(price.toPlainString());
        return written ? Optional.of(price) : Optional.empty();
    }

    /** Vade: gün sayısı alıntıda ayrı bir sayı olarak geçmeli. */
    static Optional<Integer> days(BigDecimal value, String quote) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 0 || value.intValue() > 9999
                || quote == null) {
            return Optional.empty();
        }
        int days = value.intValue();
        boolean written = Pattern.compile("(?<![\\d.,])" + days + "(?![\\d,])").matcher(normalize(quote)).find();
        return written ? Optional.of(days) : Optional.empty();
    }

    static String normalize(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFC)).replaceAll(" ").strip();
    }

    /** {@code 1.840,00} (gruplu) ya da {@code 1840,00}. */
    static String turkish(BigDecimal amount, boolean grouped) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat(grouped ? "#,##0.00" : "0.00", symbols);
        return format.format(amount);
    }
}
