package com.archosan.invoice.extraction.parse;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.regex.Pattern;

/**
 * Faturada basıldığı gibi gelen tutarı {@link BigDecimal}'a çevirir (NFR-14). LLM sayıyı çevirmez, kod çevirir
 * (B-18 K3): binlik ayırıcıyı ondalıkla karıştırmak sessizce yanlış tutar demektir.
 *
 * <ul>
 *   <li>Para birimi işaretleri ({@code TL}, {@code ₺}, {@code TRY}) ve boşluklar atılır.</li>
 *   <li>Hem nokta hem virgül varsa sondaki ondalık ayırıcıdır ({@code 1.234,56} ve {@code 1,234.56}).</li>
 *   <li>Yalnızca virgül varsa ondalıktır ({@code 410,00}).</li>
 *   <li>Yalnızca nokta varsa: {@code 1.234.567} gibi üçlü gruplar binliktir; aksi halde ondalık ({@code 86.50}).</li>
 * </ul>
 *
 * Çözülemeyen değer {@code null} döner; zorunlu alan eksikliği kural doğrulamasında (B-20) yakalanır.
 */
public final class TurkishNumbers {

    private static final Pattern CURRENCY_AND_SPACES = Pattern.compile("(?i)(TRY|TL|₺|\\s|\\u00A0)");
    private static final Pattern THOUSANDS_WITH_DOTS = Pattern.compile("^-?\\d{1,3}(\\.\\d{3})+$");
    private static final Pattern PLAIN_NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    /** Tek bir sayı ve isteğe bağlı birim kelimesi: {@code 150 kg}, {@code 3 Adet}, {@code 12,5 m²}. */
    private static final Pattern QUANTITY_WITH_UNIT =
            Pattern.compile("^\\s*(-?[\\d.,]+)\\s*(?:[\\p{L}²³.]+(?:\\s+[\\p{L}²³.]+)?)?\\s*$");
    /** Miktar alanında para birimi: birim fiyat ya da tutar yanlış alana yazılmış. */
    private static final Pattern CURRENCY = Pattern.compile("(?i)(\\bTL\\b|\\bTRY\\b|₺)");

    private TurkishNumbers() {
    }

    /**
     * KDV oranı: alandaki <b>ilk</b> sayı ({@code %20}, {@code 20 %}, {@code 20,00} → 20). LLM bazen satırın devamını
     * da bu alana yazar ({@code "1 %1 9.200,00 TL"}); {@link #parse} boşlukları sildiği için bunu 119200 okurdu (B-21
     * duman testinde gerçek Ollama ile bulundu).
     *
     * @return oran; sayı yoksa {@code null}
     */
    public static Integer parseRate(String printed) {
        if (printed == null) {
            return null;
        }
        var matcher = FIRST_NUMBER.matcher(printed);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal rate = parse(matcher.group());
        return rate == null ? null : rate.intValue();
    }

    /**
     * Kalem miktarı: sayı ve isteğe bağlı birimi ({@code 150 kg} → 150, {@code 3 Adet} → 3). Faturalarda Miktar
     * sütununda birim sık basılır ve LLM onu da yazar (B-29'da prompt v3 ile görüldü). Birden fazla sayı içeren değer
     * ({@code "3 1.750,00 TL"}: satırın devamı) ve para birimi içeren değer ({@code "12.500,00 TL"}: birim fiyat yanlış
     * alanda, B-29'da prompt v2 ile görüldü) çözülmez.
     *
     * @return miktar; çözülemezse {@code null}
     */
    public static BigDecimal parseQuantity(String printed) {
        if (printed == null || CURRENCY.matcher(printed).find()) {
            return null;
        }
        var matcher = QUANTITY_WITH_UNIT.matcher(printed);
        return matcher.matches() ? parse(matcher.group(1)) : null;
    }

    /** Türkçe biçim, iki ondalık: {@code 1.234.567,89}; kural açıklamalarında kullanılır. */
    public static String format(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", symbols).format(amount);
    }

    public static BigDecimal parse(String printed) {
        if (printed == null || printed.isBlank()) {
            return null;
        }
        String value = CURRENCY_AND_SPACES.matcher(printed).replaceAll("").replace('%', ' ').strip();
        int lastDot = value.lastIndexOf('.');
        int lastComma = value.lastIndexOf(',');
        String canonical;
        if (lastDot >= 0 && lastComma >= 0) {
            canonical = lastComma > lastDot
                    ? value.replace(".", "").replace(',', '.')
                    : value.replace(",", "");
        } else if (lastComma >= 0) {
            canonical = value.replace(',', '.');
        } else if (THOUSANDS_WITH_DOTS.matcher(value).matches()) {
            canonical = value.replace(".", "");
        } else {
            canonical = value;
        }
        return PLAIN_NUMBER.matcher(canonical).matches() ? new BigDecimal(canonical) : null;
    }
}
