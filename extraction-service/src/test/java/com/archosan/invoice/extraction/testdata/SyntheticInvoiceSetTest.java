package com.archosan.invoice.extraction.testdata;

import com.archosan.invoice.extraction.testdata.SyntheticInvoice.ExpectedOutcome;
import com.archosan.invoice.extraction.testdata.SyntheticInvoiceGenerator.ExpectedInvoiceFile;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commit edilmiş setin bütünlüğü: katalogla aynı mı (üretici yeniden çalıştırılmayı unutulmasın), toplamlar tutarlı mı
 * (bozuk fatura hariç), metin katmanı beklendiği gibi mi.
 */
class SyntheticInvoiceSetTest {

    private static final Path DIR = Path.of("src/test/resources/invoices");
    private static final JsonMapper JSON = SyntheticInvoiceGenerator.expectedJsonMapper();

    static List<SyntheticInvoice> invoices() {
        return SyntheticInvoices.all();
    }

    @Test
    void hasEightCleanAndTwoDeliberatelyBrokenInvoices() {
        Map<ExpectedOutcome, Long> outcomes = invoices().stream()
                .collect(Collectors.groupingBy(SyntheticInvoice::expectedOutcome, Collectors.counting()));

        assertThat(invoices()).hasSize(10);
        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(
                ExpectedOutcome.POSTED, 8L, ExpectedOutcome.NEEDS_REVIEW, 1L, ExpectedOutcome.NO_TEXT, 1L));
        assertThat(invoices()).extracting(i -> i.fields().supplierVkn()).doesNotHaveDuplicates()
                .allSatisfy(vkn -> assertThat(vkn).matches("\\d{10}"));
    }

    @ParameterizedTest
    @MethodSource("invoices")
    void committedExpectedJsonMatchesCatalog(SyntheticInvoice invoice) throws IOException {
        Path expected = DIR.resolve(SyntheticInvoiceGenerator.expectedFileName(invoice.file()));

        assertThat(DIR.resolve(invoice.file())).exists();
        assertThat(JSON.readValue(Files.readString(expected), ExpectedInvoiceFile.class))
                .as("Katalog değişti ama üretici yeniden çalıştırılmadı mı?")
                .isEqualTo(ExpectedInvoiceFile.of(invoice));
    }

    @ParameterizedTest
    @MethodSource("invoices")
    void amountsAreStringsInExpectedJson(SyntheticInvoice invoice) throws IOException {
        var tree = JSON.readTree(Files.readString(DIR.resolve(SyntheticInvoiceGenerator.expectedFileName(invoice.file()))));

        assertThat(tree.at("/fields/grandTotal").isString()).isTrue();
        assertThat(tree.at("/fields/lines/0/unitPrice").isString()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invoices")
    void totalsAreConsistentExceptForTheBrokenInvoice(SyntheticInvoice invoice) {
        InvoiceFields f = invoice.fields();
        BigDecimal linesSum = f.lines().stream().map(SyntheticInvoices::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vatSum = f.lines().stream().map(SyntheticInvoices::vat).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(f.subtotal().add(f.vatTotal())).isEqualByComparingTo(f.grandTotal());
        assertThat(f.invoiceDate()).isBeforeOrEqualTo(f.dueDate());
        if (invoice.expectedOutcome() == ExpectedOutcome.NEEDS_REVIEW) {
            assertThat(linesSum).isNotEqualByComparingTo(f.subtotal());
        } else {
            assertThat(linesSum).isEqualByComparingTo(f.subtotal());
            assertThat(vatSum).isEqualByComparingTo(f.vatTotal());
        }
    }

    @ParameterizedTest
    @MethodSource("invoices")
    void textLayerIsPresentExceptForTheScannedInvoice(SyntheticInvoice invoice) throws IOException {
        String text;
        try (PDDocument document = Loader.loadPDF(DIR.resolve(invoice.file()).toFile())) {
            text = new PDFTextStripper().getText(document);
        }

        if (invoice.expectedOutcome() == ExpectedOutcome.NO_TEXT) {
            assertThat(text).isBlank();
        } else {
            assertThat(text).contains(invoice.fields().invoiceNo(), invoice.fields().supplierVkn(),
                    invoice.fields().supplierName());
            assertThat(text).contains(InvoiceLayout.money(invoice.fields().grandTotal()));
        }
    }

    @Test
    void multiPageInvoiceContinuesLinesOnSecondPage() throws IOException {
        SyntheticInvoice invoice = byFile().get("invoice-07.pdf");
        try (PDDocument document = Loader.loadPDF(DIR.resolve(invoice.file()).toFile())) {
            PDFTextStripper secondPage = new PDFTextStripper();
            secondPage.setStartPage(2);
            secondPage.setEndPage(2);
            InvoiceLine last = invoice.fields().lines().getLast();

            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(secondPage.getText(document)).contains(last.description(), "Genel Toplam");
        }
    }

    @Test
    void fontLicenseShipsWithFont() {
        try (InputStream font = getClass().getResourceAsStream(SyntheticInvoiceGenerator.FONT_RESOURCE);
                InputStream license = getClass().getResourceAsStream("/fonts/DejaVuSans-LICENSE.txt")) {
            assertThat(font).isNotNull();
            assertThat(license).isNotNull();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, SyntheticInvoice> byFile() {
        return invoices().stream().collect(Collectors.toMap(SyntheticInvoice::file, Function.identity()));
    }
}
