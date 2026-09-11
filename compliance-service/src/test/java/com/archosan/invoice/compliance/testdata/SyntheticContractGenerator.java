package com.archosan.invoice.compliance.testdata;

import com.archosan.invoice.compliance.testdata.ContractText.Block;
import com.archosan.invoice.compliance.testdata.ContractText.Heading;
import com.archosan.invoice.compliance.testdata.ContractText.Item;
import com.archosan.invoice.compliance.testdata.ContractText.Paragraph;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sentetik sözleşmeleri üretir (B-44): her senaryo için {@code contract-XX.pdf} ve {@code contract-XX.expected.json}.
 * Çıktı repoya commit edilir; katalog değişince yeniden çalıştırılır:
 *
 * <pre>
 * # repo kökünde; tek çağrı: extraction test-jar'ı (B-16 kataloğu ve yazı tipi) paketlenir, test sınıfları derlenir
 * ./mvnw -q -pl compliance-service -am package -DskipTests dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 * cd compliance-service
 * java -Djava.awt.headless=true -cp "target/test-classes:target/classes:$(cat /tmp/cp.txt)" \
 *     com.archosan.invoice.compliance.testdata.SyntheticContractGenerator src/test/resources/contracts
 * </pre>
 *
 * Metin, PDFBox yazı tipi ölçüleriyle satırlara bölünür; taranmış sözleşme aynı satırları resme çizer. Türkçe
 * karakterler için B-16'nın DejaVu Sans'ı kullanılır (extraction test-jar'ı).
 */
public final class SyntheticContractGenerator {

    static final String FONT_RESOURCE = "/fonts/DejaVuSans.ttf";
    static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();

    private static final float MARGIN = 56;
    private static final float ITEM_INDENT = 14;
    private static final float BODY_TOP = PAGE_HEIGHT - 70;
    private static final float BODY_BOTTOM = 60;
    private static final float TEXT_SIZE = 10;
    private static final float HEADING_SIZE = 11;
    private static final float LINE_HEIGHT = 14;
    private static final float CHROME_SIZE = 8;
    private static final int MIN_HYPHEN_PART = 4;
    private static final float IMAGE_SCALE = 150f / 72f;

    /** Sayfaya yerleşmiş bir satır. */
    record Line(float x, float y, float size, String text) {
    }

    private SyntheticContractGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "src/test/resources/contracts");
        Files.createDirectories(outDir);
        JsonMapper json = expectedJsonMapper();
        for (SyntheticContract contract : SyntheticContracts.all()) {
            writeWithExpected(contract, outDir, json);
        }
        Path compliant = outDir.resolve(COMPLIANT_DIR);
        Files.createDirectories(compliant);
        for (SyntheticContract contract : SyntheticContracts.compliantSet()) {
            writeWithExpected(contract, compliant, json);
        }
    }

    /** Faturalarla uyumlu sözleşmeler; betikler (smoke, NFR-07) faturadan önce bunları yükler (B-46). */
    static final String COMPLIANT_DIR = "compliant";

    private static void writeWithExpected(SyntheticContract contract, Path dir, JsonMapper json) throws IOException {
        Path pdf = dir.resolve(contract.file());
        write(contract, pdf);
        Files.writeString(dir.resolve(expectedFileName(contract.file())), json.writeValueAsString(contract) + "\n");
        System.out.println("Üretildi: " + pdf);
    }

    static String expectedFileName(String pdfFile) {
        return pdfFile.replace(".pdf", ".expected.json");
    }

    /** Fatura setiyle aynı kural: tutarlar string; okunur olsun diye girintili. */
    static JsonMapper expectedJsonMapper() {
        return JsonMapper.builder()
                .withConfigOverride(BigDecimal.class,
                        o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
    }

    public static void write(SyntheticContract contract, Path target) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDType0Font font;
            try (InputStream in = fontStream()) {
                font = PDType0Font.load(document, in);
            }
            List<List<Line>> pages = layout(contract, font);
            for (List<Line> page : pages) {
                if (contract.layout().imageOnly()) {
                    drawAsImage(document, page);
                } else {
                    drawAsText(document, font, page);
                }
            }
            document.save(target.toFile());
        }
    }

    /** Blokları satırlara ve sayfalara böler; üst/alt bilgi istenmişse her sayfaya eklenir. */
    static List<List<Line>> layout(SyntheticContract contract, PDType0Font font) throws IOException {
        List<List<Line>> pages = new ArrayList<>();
        List<Line> page = new ArrayList<>();
        float y = BODY_TOP;
        boolean hyphenate = contract.layout().hyphenate();
        for (Block block : ContractText.of(contract)) {
            float size = block instanceof Heading ? HEADING_SIZE : TEXT_SIZE;
            float x = block instanceof Item ? MARGIN + ITEM_INDENT : MARGIN;
            if (block instanceof Heading && y < BODY_TOP) {
                y -= LINE_HEIGHT / 2;   // başlık öncesi boşluk
            }
            List<String> lines = wrap(text(block), font, size, PAGE_WIDTH - MARGIN - x, hyphenate);
            // Başlık sayfa sonunda yalnız kalmasın.
            float needed = block instanceof Heading ? 3 * LINE_HEIGHT : LINE_HEIGHT;
            for (String line : lines) {
                if (y - needed < BODY_BOTTOM) {
                    pages.add(page);
                    page = new ArrayList<>();
                    y = BODY_TOP;
                }
                page.add(new Line(x, y, size, line));
                y -= LINE_HEIGHT;
                needed = LINE_HEIGHT;
            }
            if (block instanceof Paragraph) {
                y -= LINE_HEIGHT / 3;
            }
        }
        pages.add(page);
        if (contract.layout().headerFooter()) {
            String header = ContractText.TITLE + " — " + contract.supplierName();
            String number = contract.file().replace(".pdf", "").toUpperCase(Locale.ROOT);
            for (int i = 0; i < pages.size(); i++) {
                pages.get(i).addFirst(new Line(MARGIN, PAGE_HEIGHT - 40, CHROME_SIZE, header));
                pages.get(i).add(new Line(MARGIN, 30, CHROME_SIZE,
                        "Sayfa " + (i + 1) + " / " + pages.size() + " · " + number + " · Gizlidir"));
            }
        }
        return pages;
    }

    private static String text(Block block) {
        return switch (block) {
            case Heading h -> h.text();
            case Paragraph p -> p.text();
            case Item i -> i.text();
        };
    }

    /**
     * Sözcük sınırından satırlara böler. {@code hyphenate} ise sığmayan uzun sözcük satır sonunda tireyle bölünür
     * (her iki parça en az dört harf): normalizasyonun birleştireceği "sözleş-\nmenin" biçimi.
     */
    static List<String> wrap(String text, PDType0Font font, float size, float width, boolean hyphenate)
            throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, font, size) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (hyphenate && word.length() >= 2 * MIN_HYPHEN_PART && Character.isLetter(word.charAt(0))) {
                String prefix = line.isEmpty() ? "" : line + " ";
                for (int cut = word.length() - MIN_HYPHEN_PART; cut >= MIN_HYPHEN_PART; cut--) {
                    String head = prefix + word.substring(0, cut) + "-";
                    if (Character.isLetter(word.charAt(cut - 1)) && Character.isLetter(word.charAt(cut))
                            && width(head, font, size) <= width) {
                        lines.add(head);
                        line.setLength(0);
                        line.append(word.substring(cut));
                        word = null;
                        break;
                    }
                }
                if (word == null) {
                    continue;
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            line.setLength(0);
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static float width(String text, PDType0Font font, float size) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    private static void drawAsText(PDDocument document, PDType0Font font, List<Line> lines) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            for (Line line : lines) {
                content.beginText();
                content.setFont(font, line.size());
                content.newLineAtOffset(line.x(), line.y());
                content.showText(line.text());
                content.endText();
            }
        }
    }

    /** Taranmış belge gibi: satırlar bir resme çizilir, sayfaya yalnızca resim konur. */
    private static void drawAsImage(PDDocument document, List<Line> lines) throws IOException {
        int width = Math.round(PAGE_WIDTH * IMAGE_SCALE);
        int height = Math.round(PAGE_HEIGHT * IMAGE_SCALE);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try (InputStream in = fontStream()) {
            Font base = Font.createFont(Font.TRUETYPE_FONT, in);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(30, 30, 30));
            for (Line line : lines) {
                g.setFont(base.deriveFont(line.size() * IMAGE_SCALE));
                g.drawString(line.text(), line.x() * IMAGE_SCALE, (PAGE_HEIGHT - line.y()) * IMAGE_SCALE);
            }
        } catch (FontFormatException e) {
            throw new IOException(e);
        } finally {
            g.dispose();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(JPEGFactory.createFromImage(document, image, 0.8f), 0, 0, PAGE_WIDTH, PAGE_HEIGHT);
        }
    }

    private static InputStream fontStream() throws IOException {
        InputStream in = SyntheticContractGenerator.class.getResourceAsStream(FONT_RESOURCE);
        if (in == null) {
            throw new IOException("Font bulunamadı (extraction test-jar'ı classpath'te mi?): " + FONT_RESOURCE);
        }
        return in;
    }
}
