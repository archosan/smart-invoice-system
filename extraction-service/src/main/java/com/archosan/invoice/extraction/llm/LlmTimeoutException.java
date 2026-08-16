package com.archosan.invoice.extraction.llm;

/** LLM çağrısı {@code llm.timeout} içinde yanıt vermedi; bir deneme sayılır (B-19). */
public class LlmTimeoutException extends RuntimeException {

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
