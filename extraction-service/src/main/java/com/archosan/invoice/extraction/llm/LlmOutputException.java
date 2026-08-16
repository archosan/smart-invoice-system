package com.archosan.invoice.extraction.llm;

/** LLM çıktısı şemaya uygun JSON olarak çözülemedi; ham çıktı hata ayıklama (ve B-21'de extraction_runs) içindir. */
public class LlmOutputException extends RuntimeException {

    private final String rawOutput;

    public LlmOutputException(String message, String rawOutput, Throwable cause) {
        super(message, cause);
        this.rawOutput = rawOutput;
    }

    public String rawOutput() {
        return rawOutput;
    }
}
