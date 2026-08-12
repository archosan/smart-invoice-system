package com.archosan.invoice.document.storage;

/** Yüklenen içerik {@code %PDF-} ile başlamıyor. */
public class NotPdfException extends RuntimeException {

    public NotPdfException() {
        super("Yüklenen dosya PDF değil");
    }
}
