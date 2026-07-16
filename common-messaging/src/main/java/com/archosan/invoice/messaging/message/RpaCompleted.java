package com.archosan.invoice.messaging.message;

import java.util.Objects;
import java.util.UUID;

/** Olay: rpa → document. {@code foundExisting} ön aramada kaydın zaten bulunduğunu söyler (v2). */
public record RpaCompleted(UUID documentId, String portalRefNo, boolean foundExisting) implements InvoiceMessage {

    public RpaCompleted {
        Objects.requireNonNull(documentId, "documentId");
    }
}
