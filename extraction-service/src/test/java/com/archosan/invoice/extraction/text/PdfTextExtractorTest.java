package com.archosan.invoice.extraction.text;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfTextExtractorTest {

    private static final Path INVOICES = Path.of("src/test/resources/invoices").toAbsolutePath();

    private final PdfTextExtractor extractor = new PdfTextExtractor(properties(INVOICES));

    static List<SyntheticInvoice> invoices() {
        return SyntheticInvoices.all();
    }

    @ParameterizedTest
    @MethodSource("invoices")
    void extractsTextFromSyntheticSetAndRecognizesScannedInvoice(SyntheticInvoice invoice) throws Exception {
        Path pdf = INVOICES.resolve(invoice.file());

        TextExtraction result = extractor.extract(pdf.toUri().toString(), sha256(Files.readAllBytes(pdf)));

        if (invoice.expectedOutcome() == SyntheticInvoice.ExpectedOutcome.NO_TEXT) {
            assertThat(result).isInstanceOfSatisfying(TextExtraction.NoText.class,
                    noText -> assertThat(noText.detail()).contains("metin katmanı yok"));
        } else {
            assertThat(result).isInstanceOfSatisfying(TextExtraction.Extracted.class, extracted -> {
                assertThat(extracted.text()).contains(invoice.fields().invoiceNo(), invoice.fields().supplierVkn(),
                        invoice.fields().supplierName(), "Genel Toplam");
                assertThat(Normalizer.isNormalized(extracted.text(), Normalizer.Form.NFC)).isTrue();
                assertThat(extracted.text()).doesNotContain("  ", "\t", "\n\n\n");
                // Tablo çizgileri grafiktir; metne karışıp başlığı bozmamalı.
                assertThat(extracted.text()).contains("Sıra Açıklama Miktar Birim Fiyat KDV % Tutar");
            });
        }
    }

    @Test
    void keepsTurkishCharactersAndLineStructure() throws Exception {
        TextExtraction.Extracted extracted = extracted("invoice-05.pdf");

        assertThat(extracted.text()).contains("Güneydoğu Çiçekçilik — Şükrü Öztürk",
                "Işıklı süs bitkisi (saksı, büyük boy)", "Gül buketi — ağır kokulu, İğdır üretimi");
        // Konuma göre sıralama aynı yükseklikteki sol ve sağ blokları tek satıra koyar.
        assertThat(extracted.text().lines())
                .contains("Güneydoğu Çiçekçilik — Şükrü Öztürk Fatura No: GND2026000078");
    }

    @Test
    void marksPagesOfMultiPageInvoice() throws Exception {
        TextExtraction.Extracted extracted = extracted("invoice-07.pdf");

        assertThat(extracted.pageCount()).isEqualTo(2);
        assertThat(extracted.text()).contains("--- Sayfa 1 ---", "--- Sayfa 2 ---");
        assertThat(extracted.text().indexOf("--- Sayfa 2 ---")).isLessThan(extracted.text().indexOf("Genel Toplam"));
    }

    @Test
    void rejectsFileWhoseHashDoesNotMatchMessage() {
        Path pdf = INVOICES.resolve("invoice-01.pdf");

        assertThatThrownBy(() -> extractor.extract(pdf.toUri().toString(), "0".repeat(64)))
                .isInstanceOf(DocumentIntegrityException.class)
                .hasMessageContaining("hash");
    }

    @Test
    void rejectsUriOutsideStorageRoot() {
        String traversal = INVOICES.toUri() + "../fonts/DejaVuSans.ttf";

        assertThatThrownBy(() -> extractor.extract(traversal, "0".repeat(64)))
                .isInstanceOf(DocumentIntegrityException.class)
                .hasMessageContaining("dışında");
        assertThatThrownBy(() -> extractor.extract("file:///etc/hosts", "0".repeat(64)))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void rejectsNonFileScheme() {
        assertThatThrownBy(() -> extractor.extract("http://evil.example/x.pdf", "0".repeat(64)))
                .isInstanceOf(DocumentIntegrityException.class)
                .hasMessageContaining("şema");
    }

    @Test
    void missingFileIsInfrastructureProblem() {
        String missing = INVOICES.resolve("yok.pdf").toUri().toString();

        assertThatThrownBy(() -> extractor.extract(missing, "0".repeat(64)))
                .isInstanceOf(DocumentNotAvailableException.class);
    }

    @Test
    void unreadablePdfIsNoTextWithDetail(@TempDir Path root) throws Exception {
        byte[] corrupt = "%PDF-1.7\nbu bir pdf değil\n".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(root.resolve("bozuk.pdf"), corrupt);

        TextExtraction result = new PdfTextExtractor(properties(root))
                .extract(file.toUri().toString(), sha256(corrupt));

        assertThat(result).isInstanceOfSatisfying(TextExtraction.NoText.class,
                noText -> assertThat(noText.detail()).startsWith("PDF okunamadı"));
    }

    @Test
    void fewStrayCharactersCountAsNoText(@TempDir Path root) throws Exception {
        Path file = root.resolve("damga.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(50, 50);
                content.showText("Sayfa 1 / 1");
                content.endText();
            }
            document.save(file.toFile());
        }

        TextExtraction result = new PdfTextExtractor(properties(root))
                .extract(file.toUri().toString(), sha256(Files.readAllBytes(file)));

        assertThat(result).isInstanceOfSatisfying(TextExtraction.NoText.class,
                noText -> assertThat(noText.detail()).contains("8 karakter"));
    }

    @Test
    void normalizesDecomposedCharactersAndWhitespace() {
        String decomposed = "Şükrü   Öztürk\t\r\n\n\n\nİğdır  ";

        assertThat(PdfTextExtractor.normalize(decomposed)).isEqualTo("Şükrü Öztürk\n\nİğdır");
    }

    private TextExtraction.Extracted extracted(String file) throws Exception {
        Path pdf = INVOICES.resolve(file);
        return (TextExtraction.Extracted) extractor.extract(pdf.toUri().toString(), sha256(Files.readAllBytes(pdf)));
    }

    private static ExtractionProperties properties(Path root) {
        return ExtractionProperties.of(root, 20,
                new ExtractionProperties.Llm("test-model", Duration.ofSeconds(1), 2, 1, Duration.ofSeconds(1), 3,
                        Duration.ofSeconds(30), 8192, 4096, "v2"));
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
