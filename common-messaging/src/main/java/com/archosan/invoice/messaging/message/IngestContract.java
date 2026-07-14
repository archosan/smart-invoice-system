package com.archosan.invoice.messaging.message;

import java.util.Objects;
import java.util.UUID;

/** İç komut: compliance → compliance (v2). */
public record IngestContract(UUID contractId, String storageUri) implements InvoiceMessage {

    public IngestContract {
        Objects.requireNonNull(contractId, "contractId");
    }
}
