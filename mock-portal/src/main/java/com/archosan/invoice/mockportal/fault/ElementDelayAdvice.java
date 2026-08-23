package com.archosan.invoice.mockportal.fault;

import com.archosan.invoice.mockportal.PortalProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Gecikmeli eleman (FR-P3, B-36): her sayfaya {@code elementDelayMs} verilir; {@code data-delayed} işaretli eleman
 * (fatura formu, sonuç tablosu) sayfa yüklendikten bu kadar sonra görünür ({@code fragments.html}). Bot elemanı DOM'da
 * değil görünür olunca kullanabilir; görünmeden tıklamaya ya da okumaya çalışan bot düşer.
 */
@ControllerAdvice
class ElementDelayAdvice {

    private final long elementDelayMs;

    ElementDelayAdvice(PortalProperties properties) {
        this.elementDelayMs = properties.elementDelay().toMillis();
    }

    @ModelAttribute("elementDelayMs")
    long elementDelayMs() {
        return elementDelayMs;
    }
}
