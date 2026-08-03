package com.archosan.invoice.document.admin;

/** İşlem park kaydının ya da belgenin şimdiki durumunda yapılamaz (409). */
public class DeadLetterConflictException extends RuntimeException {

    public DeadLetterConflictException(String message) {
        super(message);
    }
}
