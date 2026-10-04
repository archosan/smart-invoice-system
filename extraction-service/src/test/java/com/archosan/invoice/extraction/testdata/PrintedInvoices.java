package com.archosan.invoice.extraction.testdata;

import com.archosan.invoice.extraction.llm.LlmInvoice;
import com.archosan.invoice.messaging.message.InvoiceFields;

import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Bir sentetik faturanın değerlerini faturada <b>basıldığı biçimde</b> verir: iyi bir LLM'in döndüreceği çıktı. Stub
 * LLM bunu kullanır; böylece LLM → Türkçe ayrıştırıcı yolu beklenen JSON'larla karşılaştırılabilir.
 */
public final class PrintedInvoices {

    private PrintedInvoices() {
    }

    /** Metinde fatura numarası geçen sentetik fatura. */
    public static Optional<SyntheticInvoice> findByText(String text) {
        return SyntheticInvoices.all().stream()
                .filter(invoice -> text.contains(invoice.fields().invoiceNo()))
                .findFirst();
    }

    public static LlmInvoice asPrinted(SyntheticInvoice invoice) {
        InvoiceFields f = invoice.fields();
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(invoice.layout().datePattern());
        boolean liraSign = invoice.layout().currencyStyle() == SyntheticInvoice.CurrencyStyle.MIXED_LIRA_SIGN;
        return new LlmInvoice(f.supplierName(), f.supplierVkn(), f.invoiceNo(), dates.format(f.invoiceDate()),
                dates.format(f.dueDate()),
                f.lines().stream().map(line -> new LlmInvoice.Line(line.description(),
                        InvoiceLayout.quantity(line.quantity()),
                        liraSign ? "₺" + InvoiceLayout.money(line.unitPrice())
                                : InvoiceLayout.money(line.unitPrice()) + " TL",
                        "%" + line.vatRate())).toList(),
                InvoiceLayout.money(f.subtotal()) + " TL", InvoiceLayout.money(f.vatTotal()) + " TL",
                InvoiceLayout.money(f.grandTotal()) + " TL", liraSign ? "₺" : "TL");
    }
}
