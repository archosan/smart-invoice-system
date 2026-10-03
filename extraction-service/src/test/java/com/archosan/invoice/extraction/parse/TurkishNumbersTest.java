package com.archosan.invoice.extraction.parse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TurkishNumbersTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1.234.567,89 TL | 1234567.89",
            "1.234,56        | 1234.56",
            "410,00          | 410.00",
            "₺14,75          | 14.75",
            "₺1.840,00       | 1840.00",
            "86.50           | 86.50",
            "1,234.56        | 1234.56",
            "985.000         | 985000",
            "1.5             | 1.5",
            "1,5             | 1.5",
            "120             | 120",
            "12.500,00 TRY   | 12500.00",
            "-15,00 TL       | -15.00",
            "1 234,56 TL     | 1234.56"})
    void parsesPrintedAmounts(String printed, String expected) {
        assertThat(TurkishNumbers.parse(printed)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"410,00 | 2", "1.234.567,89 TL | 2", "86.50 | 2", "120 | 0"})
    void keepsPrintedScale(String printed, int scale) {
        assertThat(TurkishNumbers.parse(printed).scale()).isEqualTo(scale);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "yok", "12,34,56", "TL"})
    void unparsableIsNull(String printed) {
        assertThat(TurkishNumbers.parse(printed)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "%20              | 20",
            "20 %             | 20",
            "20               | 20",
            "%1               | 1",
            "20,00            | 20",
            "1 %1 9.200,00 TL | 1",
            "10 %             | 10"})
    void rateIsFirstNumberInField(String printed, int expected) {
        assertThat(TurkishNumbers.parseRate(printed)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"yirmi", "%"})
    void rateWithoutNumberIsNull(String printed) {
        assertThat(TurkishNumbers.parseRate(printed)).isNull();
    }

    /** B-29: Miktar sütununda birim sık basılır ve LLM onu da yazar ("150 kg"). */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "150        | 150",
            "150 kg     | 150",
            "3 Adet     | 3",
            "12,5 m²    | 12.5",
            "1.250 adet | 1250",
            "2,5 lt.    | 2.5",
            "40 çift    | 40",
            "6 koli kutu | 6"})
    void quantityIgnoresTrailingUnit(String printed, String expected) {
        assertThat(TurkishNumbers.parseQuantity(printed)).isEqualByComparingTo(expected);
    }

    /** Satırın devamı (birim fiyat) ya da açıklama miktar alanına yazılırsa çözülmez; kurallar yakalar. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"3 1.750,00 TL", "12.500,00 TL", "₺410,00", "150 TRY", "kg 150", "Adet", "6204 ZZ 24",
            "1 2 3"})
    void quantityWithMoreThanOneNumberOrNoNumberIsNull(String printed) {
        assertThat(TurkishNumbers.parseQuantity(printed)).isNull();
    }
}
