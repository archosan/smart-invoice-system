package com.archosan.invoice.document.review;

import java.util.UUID;

/** 404 döner. */
public class ReviewDocumentNotFoundException extends RuntimeException {

    public ReviewDocumentNotFoundException(UUID id) {
        super("Kayıt bulunamadı: " + id);
    }
}
