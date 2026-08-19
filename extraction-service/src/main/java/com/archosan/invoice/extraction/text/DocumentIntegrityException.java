package com.archosan.invoice.extraction.text;

/**
 * Mesajdaki dosya adresi kabul edilemez (şema {@code file} değil, depo kökünün dışı) veya dosyanın hash'i mesajdakiyle
 * uyuşmuyor. Kalıcıdır; tekrar denemek sonucu değiştirmez.
 */
public class DocumentIntegrityException extends RuntimeException {

    public DocumentIntegrityException(String message) {
        super(message);
    }
}
