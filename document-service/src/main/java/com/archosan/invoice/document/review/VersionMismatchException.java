package com.archosan.invoice.document.review;

/** {@code If-Match} sürümü kaydın sürümü değil (başkası değiştirmiş); 412 döner. */
public class VersionMismatchException extends RuntimeException {

    public VersionMismatchException(int expected, int actual) {
        super("Kayıt değişmiş: If-Match " + expected + ", güncel sürüm " + actual);
    }
}
