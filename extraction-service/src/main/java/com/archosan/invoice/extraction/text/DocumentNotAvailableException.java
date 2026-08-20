package com.archosan.invoice.extraction.text;

/**
 * Dosya yok veya okunamıyor. Altyapı sorunu sayılır (volume bağlanmamış gibi): mesaj geri konur, sorun sürerse DLQ'ya
 * düşer ve kayıt {@code NEEDS_REVIEW} olur (B-14).
 */
public class DocumentNotAvailableException extends RuntimeException {

    public DocumentNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
