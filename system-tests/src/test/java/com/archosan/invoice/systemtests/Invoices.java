package com.archosan.invoice.systemtests;

import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoiceGenerator;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import com.archosan.invoice.messaging.message.InvoiceFields;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/** Test faturaları: B-16 seti (extraction test-jar'ından) ve testin ürettiği faturalar. */
final class Invoices {

    private Invoices() {
    }

    static SyntheticInvoice byFile(String file) {
        return SyntheticInvoices.all().stream().filter(i -> i.file().equals(file)).findFirst().orElseThrow();
    }

    /**
     * Aynı fatura, aynı düzende, yeni ve benzersiz bir fatura no ile ({@code ST-…}); sahte LLM'e tanıtılır. Yığın
     * testler arasında paylaşıldığı için her yükleme yeni bir faturadır, içerik bazlı mükerrer şüphesine (B-41)
     * takılmaz ve portal ön araması (B-35) önceki testin kaydını bulmaz. Yeni numara eskisini içermez: sahte LLM
     * faturayı istemdeki numaradan tanır.
     */
    static SyntheticInvoice renumbered(SyntheticInvoice base, FakeLlm llm) {
        return withNumber(base, "ST-" + randomSuffix(), base.file(), base.scenario(), base.note(), llm);
    }

    /**
     * B-16'nın ilk faturasıyla aynı düzende, numarası {@code FAIL-…} olan yeni bir fatura: portal onu hep reddeder
     * (hata bayrağı), sahte LLM'e tanıtılır.
     */
    static SyntheticInvoice failingAtPortal(FakeLlm llm) {
        return withPrefix(SystemStack.FAILING_PREFIX, "Portalda hep hata", llm);
    }

    /**
     * Numarası {@code SLOW-…} olan fatura: portal onu kaydeder ama yanıtı geciktirir (B-35); bot bu arada öldürülürse
     * portalda kayıt vardır, sistemde yoktur (US-08).
     */
    static SyntheticInvoice slowAtPortal(FakeLlm llm) {
        return withPrefix(SystemStack.SLOW_PREFIX, "Portal kaydedip geç yanıt verir", llm);
    }

    /**
     * Aynı fatura, yeni fatura no ve verilen tedarikçi VKN'siyle (B-46): uyum senaryoları kendi sözleşmesini yükler,
     * diğer testlerin sözleşmeleriyle karışmaz.
     */
    static SyntheticInvoice forSupplier(SyntheticInvoice base, String vkn, FakeLlm llm) {
        InvoiceFields f = base.fields();
        String invoiceNo = "ST-" + randomSuffix();
        SyntheticInvoice invoice = new SyntheticInvoice(invoiceNo + ".pdf", base.scenario(), base.expectedOutcome(),
                new InvoiceFields(f.supplierName(), vkn, invoiceNo, f.invoiceDate(), f.dueDate(), f.lines(),
                        f.subtotal(), f.vatTotal(), f.grandTotal(), f.currency()),
                base.layout(), "B-46");
        llm.know(invoice);
        return invoice;
    }

    /** Rastgele, 10 haneli, sıfırla başlamayan VKN. */
    static String randomVkn() {
        return String.valueOf(1_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(),
                9_000_000_000L));
    }

    private static SyntheticInvoice withPrefix(String prefix, String scenario, FakeLlm llm) {
        String invoiceNo = prefix + randomSuffix();
        return withNumber(byFile("invoice-01.pdf"), invoiceNo, invoiceNo + ".pdf", scenario, "B-28, B-35", llm);
    }

    private static SyntheticInvoice withNumber(SyntheticInvoice base, String invoiceNo, String file, String scenario,
            String note, FakeLlm llm) {
        InvoiceFields f = base.fields();
        SyntheticInvoice invoice = new SyntheticInvoice(file, scenario, base.expectedOutcome(),
                new InvoiceFields(f.supplierName(), f.supplierVkn(), invoiceNo, f.invoiceDate(), f.dueDate(), f.lines(),
                        f.subtotal(), f.vatTotal(), f.grandTotal(), f.currency()),
                base.layout(), note);
        llm.know(invoice);
        return invoice;
    }

    private static String randomSuffix() {
        return HexFormat.of().withUpperCase()
                .formatHex(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII), 0, 5);
    }

    /** Üretilmiş faturanın PDF'i (her çağrıda benzersiz kopya). */
    static byte[] render(SyntheticInvoice invoice) {
        try {
            Path pdf = Files.createTempFile("system-tests", ".pdf");
            SyntheticInvoiceGenerator.write(invoice, pdf);
            byte[] bytes = Files.readAllBytes(pdf);
            Files.deleteIfExists(pdf);
            return withMarker(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] withMarker(byte[] pdf) {
        byte[] marker = ("\n%system-test " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] copy = Arrays.copyOf(pdf, pdf.length + marker.length);
        System.arraycopy(marker, 0, copy, pdf.length, marker.length);
        return copy;
    }
}
