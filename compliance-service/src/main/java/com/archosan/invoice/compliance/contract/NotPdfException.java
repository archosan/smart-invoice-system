package com.archosan.invoice.compliance.contract;

/** Yüklenen içerik {@code %PDF-} ile başlamıyor; 415 döner. */
public class NotPdfException extends RuntimeException {

    public NotPdfException() {
        super("Yalnızca PDF kabul edilir");
    }
}
