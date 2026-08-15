package com.archosan.invoice.extraction.llm;

import java.util.List;

/**
 * Bir prompt sürümü. Sürüm {@code extraction_runs.prompt_version}'a ve {@code ExtractionCompleted.promptVersion}'a
 * yazılır; metin değişirse sürüm de değişir. Eski sürümler kalibrasyon kaydı olarak kodda kalır ve ayarla seçilebilir
 * ({@code invoice.extraction.llm.prompt-version}, B-29).
 */
public interface InvoicePrompt {

    String version();

    String system();

    String user(String invoiceText);

    /** Yeniden denemede önceki yanıtın hatası geri beslenir (B-19). */
    String retry(String error);

    static InvoicePrompt of(String version) {
        return List.of(PromptV1.INSTANCE, PromptV2.INSTANCE, PromptV3.INSTANCE).stream()
                .filter(prompt -> prompt.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Bilinmeyen prompt sürümü (invoice.extraction.llm.prompt-version): " + version));
    }
}
