package com.archosan.invoice.document.settings;

/** Ayarlar API'sine geçersiz değer geldi; 400 döner. */
public class InvalidSettingException extends RuntimeException {

    public InvalidSettingException(String message) {
        super(message);
    }
}
