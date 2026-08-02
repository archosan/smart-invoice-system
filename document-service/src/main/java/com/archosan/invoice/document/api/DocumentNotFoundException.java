package com.archosan.invoice.document.api;

import java.util.UUID;

class DocumentNotFoundException extends RuntimeException {

    DocumentNotFoundException(UUID id) {
        super("Kayıt bulunamadı: " + id);
    }
}
