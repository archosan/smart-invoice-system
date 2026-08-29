package com.archosan.invoice.rpa.portal;

/**
 * Geçici sayılan portal hatası (hata sayfası, beklenmeyen sayfa). Mesaj geri konur; teslim limitinden sonra DLQ
 * (v1). v2'de servis içi artan beklemeli retry (FR-R5).
 */
public class PortalErrorException extends RuntimeException {

    public PortalErrorException(String message) {
        super(message);
    }
}
