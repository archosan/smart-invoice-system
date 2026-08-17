package com.archosan.invoice.extraction.parse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Faturada basıldığı gibi gelen tarihi çevirir: {@code gg.aa.yyyy}, {@code gg/aa/yyyy}, {@code gg-aa-yyyy},
 * {@code yyyy-aa-gg}. Çözülemeyen değer {@code null} döner.
 */
public final class TurkishDates {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private TurkishDates() {
    }

    public static LocalDate parse(String printed) {
        if (printed == null || printed.isBlank()) {
            return null;
        }
        String value = printed.strip();
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // sıradaki biçim
            }
        }
        return null;
    }
}
