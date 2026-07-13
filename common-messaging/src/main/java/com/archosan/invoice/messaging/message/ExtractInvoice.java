package com.archosan.invoice.messaging.message;

import java.util.Objects;
import java.util.UUID;

/** Komut: document → extraction. */
public record ExtractInvoice(UUID documentId, String storageUri, String fileSha256) implements InvoiceMessage {

    public ExtractInvoice {
        Objects.requireNonNull(documentId, "documentId");
    }
}
