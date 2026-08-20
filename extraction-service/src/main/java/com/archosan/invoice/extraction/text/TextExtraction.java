package com.archosan.invoice.extraction.text;

/** Metin çıkarımının sonucu (FR-E1). */
public sealed interface TextExtraction {

    /** LLM'e gidecek normalize edilmiş metin; sayfalar {@code --- Sayfa n ---} ile ayrılır. */
    record Extracted(String text, int pageCount) implements TextExtraction {
    }

    /**
     * Metin yok, yetersiz ya da PDF açılamadı; {@code ExtractionFailed(NO_TEXT, detail)} olur. Ayrım {@code detail}
     * alanındadır (B-17: mesaj sözleşmesi değişmesin diye ayrı bir neden eklenmedi).
     */
    record NoText(String detail) implements TextExtraction {
    }
}
