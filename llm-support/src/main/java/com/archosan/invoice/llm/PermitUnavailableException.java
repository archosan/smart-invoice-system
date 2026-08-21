package com.archosan.invoice.llm;

/** LLM izni süresinde alınamadı; geçicidir, mesaj geri konur. */
public class PermitUnavailableException extends RuntimeException {

    public PermitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
