package com.archosan.invoice.extraction.pipeline;

import com.archosan.invoice.extraction.llm.LlmInvoice;
import com.archosan.invoice.messaging.message.InvoiceFields;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceMapperTest {

    @Test
    void mapsPrintedValuesToTypedFields() {
        LlmInvoice llm = new LlmInvoice(" ACME A.Ş. ", "VKN 1234 567 890", "F-1", "18/09/2026", "30.10.2026",
                List.of(new LlmInvoice.Line("Rulman", "1,5", "₺1.840,00", "%20"),
                        new LlmInvoice.Line("Ekmek", "40", "12,50 TL", "1")),
                "3.260,00 TL", "556,00 TL", "3.816,00 TL", "₺");

        InvoiceFields fields = InvoiceMapper.map(llm);

        assertThat(fields.supplierName()).isEqualTo("ACME A.Ş.");
        assertThat(fields.supplierVkn()).isEqualTo("1234567890");
        assertThat(fields.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(fields.dueDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(fields.lines().getFirst().quantity()).isEqualByComparingTo("1.5");
        assertThat(fields.lines().getFirst().unitPrice()).isEqualByComparingTo("1840.00");
        assertThat(fields.lines()).extracting(l -> l.vatRate()).containsExactly(20, 1);
        assertThat(fields.grandTotal()).isEqualTo(new BigDecimal("3816.00"));
        assertThat(fields.currency()).isEqualTo("TRY");
    }

    @Test
    void unparsableAndMissingValuesBecomeNullNotErrors() {
        LlmInvoice llm = new LlmInvoice("", "", "", "bilinmiyor", "", null, "yok", "", "", "");

        InvoiceFields fields = InvoiceMapper.map(llm);

        assertThat(fields.supplierName()).isNull();
        assertThat(fields.supplierVkn()).isNull();
        assertThat(fields.invoiceDate()).isNull();
        assertThat(fields.lines()).isEmpty();
        assertThat(fields.subtotal()).isNull();
        assertThat(fields.currency()).isNull();
    }

    @Test
    void foreignCurrencyIsKeptUppercase() {
        assertThat(InvoiceMapper.currency("eur")).isEqualTo("EUR");
        assertThat(InvoiceMapper.currency("TL")).isEqualTo("TRY");
    }
}
