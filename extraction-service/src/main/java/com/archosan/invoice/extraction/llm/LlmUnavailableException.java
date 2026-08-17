package com.archosan.invoice.extraction.llm;

/**
 * Ollama'ya hiç ulaşılamadı (bağlantı reddi, host yok). Deneme sayılmaz; altyapı sorunudur (A1): mesaj geri konur,
 * dinleyici Ollama dönene kadar durdurulur ({@code OllamaAvailability}).
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
