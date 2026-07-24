package com.archosan.invoice.compliance.api;

import java.util.UUID;

class ContractNotFoundException extends RuntimeException {

    ContractNotFoundException(UUID id) {
        super("Sözleşme bulunamadı: " + id);
    }
}
