package com.archosan.invoice.extraction.parse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TurkishDatesTest {

    @ParameterizedTest
    @ValueSource(strings = {"18.09.2026", "18/09/2026", "18-09-2026", "2026-09-18", " 18.9.2026 "})
    void parsesPrintedDates(String printed) {
        assertThat(TurkishDates.parse(printed)).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"32.09.2026", "Eylül 2026", "18.09.26x"})
    void unparsableIsNull(String printed) {
        assertThat(TurkishDates.parse(printed)).isNull();
    }
}
