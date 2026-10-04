package com.archosan.invoice.extraction.testdata;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sentetik faturaları üretir (B-16): her senaryo için {@code invoice-XX.pdf} ve {@code invoice-XX.expected.json}.
 * Çıktı repoya commit edilir; senaryo değişince yeniden çalıştırılır:
 *
 * <pre>
 * # repo kökünde; derleme ve classpath aynı çağrıda olmalı (kardeş modüller ancak öyle çözülür)
 * ./mvnw -q -pl extraction-service -am test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 * cd extraction-service
 * java -Djava.awt.headless=true -cp "target/test-classes:target/classes:$(cat /tmp/cp.txt)" \
 *     com.archosan.invoice.extraction.testdata.SyntheticInvoiceGenerator src/test/resources/invoices
 * </pre>
 *
 * Türkçe karakterler için DejaVu Sans gömülür ({@code src/test/resources/fonts}, lisansı yanında). Metinsiz fatura
 * sayfayı resim (JPEG) olarak çizer.
 */
public final class SyntheticInvoiceGenerator {

    static final String FONT_RESOURCE = "/fonts/DejaVuSans.ttf";
    private static final float IMAGE_SCALE = 150f / 72f;

    /** Beklenen JSON dosyasının biçimi. */
    public record ExpectedInvoiceFile(
            String file,
            String scenario,
            SyntheticInvoice.ExpectedOutcome expectedOutcome,
            com.archosan.invoice.messaging.message.InvoiceFields fields,
            String note) {

        static ExpectedInvoiceFile of(SyntheticInvoice invoice) {
            return new ExpectedInvoiceFile(invoice.file(), invoice.scenario(), invoice.expectedOutcome(),
                    invoice.fields(), invoice.note());
        }
    }

    private SyntheticInvoiceGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "src/test/resources/invoices");
        Files.createDirectories(outDir);
        JsonMapper json = expectedJsonMapper();
        for (SyntheticInvoice invoice : SyntheticInvoices.all()) {
            Path pdf = outDir.resolve(invoice.file());
            write(invoice, pdf);
            Files.writeString(outDir.resolve(expectedFileName(invoice.file())),
                    json.writeValueAsString(ExpectedInvoiceFile.of(invoice)) + "\n");
            System.out.println("Üretildi: " + pdf);
        }
    }

    static String expectedFileName(String pdfFile) {
        return pdfFile.replace(".pdf", ".expected.json");
    }

    /** Mesajlarla aynı kural: tutarlar string; okunur olsun diye girintili. */
    static JsonMapper expectedJsonMapper() {
        return JsonMapper.builder()
                .withConfigOverride(BigDecimal.class,
                        o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    /** Faturayı PDF olarak yazar; system-tests B-16'da olmayan faturalar için de kullanır (B-28). */
    public static void write(SyntheticInvoice invoice, Path target) throws IOException {
        List<List<InvoiceLayout.Element>> pages = InvoiceLayout.of(invoice);
        try (PDDocument document = new PDDocument()) {
            if (invoice.layout().imageOnly()) {
                for (List<InvoiceLayout.Element> page : pages) {
                    drawAsImage(document, page);
                }
            } else {
                PDType0Font font;
                try (InputStream in = fontStream()) {
                    font = PDType0Font.load(document, in);
                }
                for (List<InvoiceLayout.Element> page : pages) {
                    drawAsText(document, font, page);
                }
            }
            document.save(target.toFile());
        }
    }

    private static void drawAsText(PDDocument document, PDType0Font font, List<InvoiceLayout.Element> elements)
            throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            for (InvoiceLayout.Element element : elements) {
                switch (element) {
                    case InvoiceLayout.Text text -> {
                        content.beginText();
                        content.setFont(font, text.size());
                        content.newLineAtOffset(text.x(), text.y());
                        content.showText(text.value());
                        content.endText();
                    }
                    case InvoiceLayout.Rule rule -> {
                        content.setLineWidth(0.5f);
                        content.moveTo(rule.x1(), rule.y());
                        content.lineTo(rule.x2(), rule.y());
                        content.stroke();
                    }
                }
            }
        }
    }

    /** Taranmış belge gibi: metin bir resme çizilir, sayfaya yalnızca resim konur. */
    private static void drawAsImage(PDDocument document, List<InvoiceLayout.Element> elements) throws IOException {
        int width = Math.round(InvoiceLayout.PAGE_WIDTH * IMAGE_SCALE);
        int height = Math.round(InvoiceLayout.PAGE_HEIGHT * IMAGE_SCALE);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try (InputStream in = fontStream()) {
            Font base = Font.createFont(Font.TRUETYPE_FONT, in);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(30, 30, 30));
            for (InvoiceLayout.Element element : elements) {
                switch (element) {
                    case InvoiceLayout.Text text -> {
                        g.setFont(base.deriveFont(text.size() * IMAGE_SCALE));
                        g.drawString(text.value(), text.x() * IMAGE_SCALE,
                                (InvoiceLayout.PAGE_HEIGHT - text.y()) * IMAGE_SCALE);
                    }
                    case InvoiceLayout.Rule rule -> {
                        int y = Math.round((InvoiceLayout.PAGE_HEIGHT - rule.y()) * IMAGE_SCALE);
                        g.drawLine(Math.round(rule.x1() * IMAGE_SCALE), y, Math.round(rule.x2() * IMAGE_SCALE), y);
                    }
                }
            }
        } catch (FontFormatException e) {
            throw new IOException(e);
        } finally {
            g.dispose();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(JPEGFactory.createFromImage(document, image, 0.8f), 0, 0,
                    InvoiceLayout.PAGE_WIDTH, InvoiceLayout.PAGE_HEIGHT);
        }
    }

    private static InputStream fontStream() throws IOException {
        InputStream in = SyntheticInvoiceGenerator.class.getResourceAsStream(FONT_RESOURCE);
        if (in == null) {
            throw new IOException("Font bulunamadı: " + FONT_RESOURCE);
        }
        return in;
    }
}
