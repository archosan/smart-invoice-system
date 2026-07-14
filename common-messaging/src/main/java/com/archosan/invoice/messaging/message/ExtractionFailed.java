package com.archosan.invoice.messaging.message;

import java.util.Objects;
import java.util.UUID;

/** Olay: extraction → document. Bir iş sonucudur, DLQ değil (US-05). */
public record ExtractionFailed(UUID documentId, Reason reason, String detail, int attempts) implements InvoiceMessage {

    public ExtractionFailed {
        Objects.requireNonNull(documentId, "documentId");
    }

    public enum Reason {
        NO_TEXT,
        LLM_RETRIES_EXHAUSTED
    }
}
