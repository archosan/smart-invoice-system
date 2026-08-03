package com.archosan.invoice.document.api;

/** Geçersiz sorgu parametresi (sayfa, boyut, tarih aralığı). */
class InvalidQueryException extends RuntimeException {

    InvalidQueryException(String message) {
        super(message);
    }
}
