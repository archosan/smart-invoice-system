package com.archosan.invoice.document.review;

/** Ret gerekçesiz; 400 döner. */
public class MissingReasonException extends RuntimeException {

    public MissingReasonException() {
        super("Ret gerekçesi zorunlu");
    }
}
