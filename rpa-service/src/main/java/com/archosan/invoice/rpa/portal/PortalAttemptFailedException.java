package com.archosan.invoice.rpa.portal;

/**
 * Bir portal denemesi başarısız oldu (B-37, FR-R6): hangi adımda düştüğü ve o anki sayfanın ekran görüntüsü. Asıl hata
 * {@link #getCause()}'dur ve türü kararı belirler: {@link PortalRejectedException} kalıcı, {@link PortalErrorException}
 * ve Playwright hataları geçicidir.
 */
public class PortalAttemptFailedException extends RuntimeException {

    private final String step;
    private final byte[] screenshot;

    public PortalAttemptFailedException(String step, byte[] screenshot, RuntimeException cause) {
        super("adım=" + step + ": " + cause.getMessage(), cause);
        this.step = step;
        this.screenshot = screenshot;
    }

    public String step() {
        return step;
    }

    /** PNG; sayfa yoksa ya da çekilemediyse {@code null}. */
    public byte[] screenshot() {
        return screenshot;
    }

    @Override
    public synchronized RuntimeException getCause() {
        return (RuntimeException) super.getCause();
    }
}
