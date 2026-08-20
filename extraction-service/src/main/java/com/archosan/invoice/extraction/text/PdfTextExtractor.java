package com.archosan.invoice.extraction.text;

import com.archosan.invoice.extraction.ExtractionProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * PDF'ten deterministik metin çıkarımı (FR-E1, B-17).
 *
 * <ol>
 *   <li>{@code storageUri} yalnızca {@code file:} şemasında ve depo kökünün içinde olabilir.</li>
 *   <li>Dosyanın SHA-256'sı mesajdakiyle karşılaştırılır; yanlış dosya LLM'e gitmez.</li>
 *   <li>Metin konuma göre sıralı çıkarılır (sütunlu düzen), sayfalar ayrılır; Unicode NFC (İ/ı), boşluklar
 *       sadeleşir.</li>
 *   <li>Boşluk dışı {@code minTextChars}'tan az karakter veya açılamayan PDF (bozuk, şifreli) {@link
 *       TextExtraction.NoText} olur.</li>
 * </ol>
 */
@Component
public class PdfTextExtractor {

    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final Path root;
    private final int minTextChars;

    public PdfTextExtractor(ExtractionProperties properties) {
        this.root = properties.storageDir().toAbsolutePath().normalize();
        this.minTextChars = properties.minTextChars();
    }

    /**
     * @throws DocumentIntegrityException     adres kabul edilemez veya hash uyuşmuyor
     * @throws DocumentNotAvailableException  dosya yok veya okunamıyor
     */
    public TextExtraction extract(String storageUri, String expectedSha256) {
        Path file = resolve(storageUri);
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DocumentNotAvailableException("Dosya okunamadı: " + storageUri, e);
        }
        String actualSha256 = sha256(content);
        if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new DocumentIntegrityException(
                    "Dosyanın hash'i mesajdakiyle uyuşmuyor: beklenen " + expectedSha256 + ", okunan " + actualSha256);
        }

        try (PDDocument document = Loader.loadPDF(content)) {
            String text = normalize(textByPage(document));
            long visible = text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
            if (visible < minTextChars) {
                return new TextExtraction.NoText(
                        "PDF'te metin katmanı yok veya yetersiz (" + visible + " karakter, " + document.getNumberOfPages()
                                + " sayfa); taranmış belge olabilir, OCR kapsam dışı");
            }
            return new TextExtraction.Extracted(text, document.getNumberOfPages());
        } catch (IOException e) {
            return new TextExtraction.NoText("PDF okunamadı: " + e.getMessage());
        }
    }

    private Path resolve(String storageUri) {
        URI uri;
        try {
            uri = URI.create(storageUri);
        } catch (IllegalArgumentException e) {
            throw new DocumentIntegrityException("Geçersiz storageUri: " + storageUri);
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new DocumentIntegrityException("Desteklenmeyen storageUri şeması: " + storageUri);
        }
        Path file = Path.of(uri).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new DocumentIntegrityException("storageUri depo kökünün dışında: " + storageUri);
        }
        return file;
    }

    private static String textByPage(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            if (document.getNumberOfPages() > 1) {
                text.append("--- Sayfa ").append(page).append(" ---\n");
            }
            text.append(stripper.getText(document)).append('\n');
        }
        return text.toString();
    }

    static String normalize(String raw) {
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder lines = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            lines.append(HORIZONTAL_SPACE.matcher(line).replaceAll(" ").strip()).append('\n');
        }
        return EXTRA_BLANK_LINES.matcher(lines.toString()).replaceAll("\n\n").strip();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
