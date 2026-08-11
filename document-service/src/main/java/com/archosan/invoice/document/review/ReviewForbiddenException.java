package com.archosan.invoice.document.review;

/** Kullanıcının rolü kaydın durumundaki eyleme yetmiyor ya da kendi yüklediğini onaylıyor (B-43); 403 döner. */
public class ReviewForbiddenException extends RuntimeException {

    public ReviewForbiddenException(String message) {
        super(message);
    }
}
