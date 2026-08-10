package com.archosan.invoice.document.review;

/** Kayıt eylemin istediği durumda değil; 409 döner. */
public class ReviewConflictException extends RuntimeException {

    public ReviewConflictException(String message) {
        super(message);
    }
}
