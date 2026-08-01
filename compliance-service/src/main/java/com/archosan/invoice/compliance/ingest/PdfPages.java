package com.archosan.invoice.compliance.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sözleşme PDF'inin metni, sayfa sayfa (FR-C1 adım 1). Paragraf sonu boş satırdır: PDFBox satırlar arası boşluğu
 * paragraf sınırı sayar (varsayılan eşik), uzun madde bu sınırdan bölünür. Sayfa numarası chunk'a yazılır.
 */
final class PdfPages {

    private PdfPages() {
    }

    /** @return her eleman bir sayfanın metni; metin katmanı yoksa boş metinler */
    static List<String> read(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            stripper.setParagraphEnd("\n");
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            return pages;
        }
    }
}
