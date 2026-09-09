package com.archosan.invoice.compliance.check;

import com.archosan.invoice.compliance.contract.ContractRepository.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroundingTest {

    private static final List<RetrievedChunk> CHUNKS = List.of(new RetrievedChunk(1, "4", """
            MADDE 4 – FİYATLAR

            • Hidrolik yağ 20 L: 1.840,00 TL / bidon"""));

    @Test
    void quoteMustAppearInARetrievedChunkIgnoringWhitespace() {
        assertThat(Grounding.isQuoted("•  Hidrolik yağ 20 L:\n1.840,00 TL / bidon", CHUNKS)).isTrue();
        assertThat(Grounding.isQuoted("Hidrolik yağ 20 L: 1.800,00 TL / bidon", CHUNKS)).isFalse();
        assertThat(Grounding.isQuoted("TL", CHUNKS)).as("çok kısa").isFalse();
        assertThat(Grounding.isQuoted(null, CHUNKS)).isFalse();
    }

    @Test
    void priceMustBeWrittenInTheQuote() {
        String quote = "Hidrolik yağ 20 L: 1.840,00 TL / bidon";

        assertThat(Grounding.price(new BigDecimal("1840.00"), quote))
                .hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("1840"));
        assertThat(Grounding.price(new BigDecimal("1840"), quote)).isPresent();
        assertThat(Grounding.price(new BigDecimal("1800.00"), quote)).isEmpty();
        assertThat(Grounding.price(null, quote)).isEmpty();
    }

    @Test
    void daysMustBeASeparateNumberInTheQuote() {
        String quote = "Fatura bedeli fatura tarihinden itibaren 30 (otuz) gün içinde ödenir.";

        assertThat(Grounding.days(new BigDecimal("30"), quote)).contains(30);
        assertThat(Grounding.days(new BigDecimal("3"), quote)).isEmpty();
        assertThat(Grounding.days(new BigDecimal("30.5"), quote)).isEmpty();
    }
}
