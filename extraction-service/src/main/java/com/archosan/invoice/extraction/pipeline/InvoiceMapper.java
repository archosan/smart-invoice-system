package com.archosan.invoice.extraction.pipeline;

import com.archosan.invoice.extraction.llm.LlmInvoice;
import com.archosan.invoice.extraction.parse.TurkishDates;
import com.archosan.invoice.extraction.parse.TurkishNumbers;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * LLM'in basıldığı gibi döndürdüğü değerleri mesaj alanlarına çevirir. Çözülemeyen değer {@code null} kalır; eksikler
 * kural doğrulamasında (B-20) yakalanır, burada reddedilmez.
 */
final class InvoiceMapper {

    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    private InvoiceMapper() {
    }

    static InvoiceFields map(LlmInvoice llm) {
        List<InvoiceLine> lines = llm.lines() == null ? List.of() : llm.lines().stream()
                .map(line -> new InvoiceLine(blankToNull(line.description()),
                        TurkishNumbers.parseQuantity(line.quantity()), TurkishNumbers.parse(line.unitPrice()),
                        vatRate(line.vatRate())))
                .toList();
        return new InvoiceFields(
                blankToNull(llm.supplierName()),
                digitsOnly(llm.supplierVkn()),
                blankToNull(llm.invoiceNo()),
                TurkishDates.parse(llm.invoiceDate()),
                TurkishDates.parse(llm.dueDate()),
                lines,
                TurkishNumbers.parse(llm.subtotal()),
                TurkishNumbers.parse(llm.vatTotal()),
                TurkishNumbers.parse(llm.grandTotal()),
                currency(llm.currency()));
    }

    /** {@code TL}, {@code ₺}, {@code TRY} → {@code TRY}; diğerleri büyük harfle olduğu gibi (kur çevrimi yok). */
    static String currency(String printed) {
        if (printed == null || printed.isBlank()) {
            return null;
        }
        String value = printed.strip().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "TL", "₺", "TRY", "TÜRK LİRASI" -> "TRY";
            default -> value;
        };
    }

    /** Alandaki ilk sayı ({@link TurkishNumbers#parseRate}); çözülemezse 0 (kural doğrulaması yakalar). */
    private static int vatRate(String printed) {
        Integer rate = TurkishNumbers.parseRate(printed);
        return rate == null ? 0 : rate;
    }

    /** VKN'den boşluk vb. atılır; 10 hane kuralı B-20'de. */
    private static String digitsOnly(String printed) {
        String digits = printed == null ? "" : NON_DIGITS.matcher(printed).replaceAll("");
        return digits.isEmpty() ? null : digits;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
