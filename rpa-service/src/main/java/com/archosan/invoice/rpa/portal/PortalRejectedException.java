package com.archosan.invoice.rpa.portal;

/**
 * Kalıcı hata: fatura bu değerlerle portala girilemez (portalın form doğrulaması veya portal biçimine çevrilemeyen
 * değer). Aynı veriyle yeniden denemek sonucu değiştirmez; mesaj doğrudan DLQ'ya gider.
 */
public class PortalRejectedException extends RuntimeException {

    public PortalRejectedException(String message) {
        super(message);
    }
}
