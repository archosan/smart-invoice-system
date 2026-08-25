package com.archosan.invoice.mockportal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * mock-portal ayarları ({@code mock-portal.*}).
 *
 * @param username             tek portal kullanıcısı ({@code PORTAL_USERNAME})
 * @param password             şifresi ({@code PORTAL_PASSWORD}); loga ve hata mesajına yazılmaz
 * @param pageSize             fatura listesinde sayfa başına satır
 * @param failInvoiceNoPattern testler için hata bayrağı: fatura numarasında bulunan (find) form gönderimi 500 döner;
 *                             boşsa kapalı (B-24). Belirli bir faturanın hep düşmesi, arkasındakilerin işlenmesi
 *                             sınanabilsin diye genel değil fatura bazlıdır (B-28)
 * @param slowInvoiceNoPattern testler için: eşleşen gönderim <b>kaydedilir</b>, yanıt {@code slowResponseDelay} kadar
 *                             gecikir; boşsa kapalı (B-35). Bot formu gönderdikten sonra, kayıt numarasını okumadan
 *                             önce öldürülebilsin diye (US-08: portalda kayıt var, sistemde yok)
 * @param slowResponseDelay    yavaş yanıtın gecikmesi
 * @param sessionIdleTimeout   oturum bu kadar boşta kalırsa düşer, sonraki istek login'e yönlenir (FR-P3, B-36).
 *                             Saniye hassasiyetinde, portalın kendisinde uygulanır (Tomcat'in süresi dakikalıktır)
 * @param errorRate            her sayfa isteğinin (login, liste, arama, form; health hariç) 500 dönme olasılığı,
 *                             0–1 (FR-P3, B-36)
 * @param errorSeed            verilirse rastgele hatalar bu tohumla üretilir; testler için tekrarlanabilir dizi
 * @param elementDelay         fatura formu ve sonuç tablosu sayfaya bu kadar sonra görünür (FR-P3, B-36); bot
 *                             elemanı beklemek zorunda kalır
 */
@ConfigurationProperties("mock-portal")
public record PortalProperties(
        String username,
        String password,
        @DefaultValue("10") int pageSize,
        String failInvoiceNoPattern,
        String slowInvoiceNoPattern,
        @DefaultValue("20s") Duration slowResponseDelay,
        @DefaultValue("30m") Duration sessionIdleTimeout,
        @DefaultValue("0") double errorRate,
        Long errorSeed,
        @DefaultValue("0ms") Duration elementDelay) {

    public PortalProperties {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "mock-portal.username ve mock-portal.password gerekli (PORTAL_USERNAME, PORTAL_PASSWORD)");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("mock-portal.page-size en az 1 olmalı");
        }
        pattern(failInvoiceNoPattern);
        pattern(slowInvoiceNoPattern);
        if (errorRate < 0 || errorRate > 1) {
            throw new IllegalArgumentException("mock-portal.error-rate 0 ile 1 arasında olmalı: " + errorRate);
        }
        if (sessionIdleTimeout.isNegative() || sessionIdleTimeout.isZero() || elementDelay.isNegative()) {
            throw new IllegalArgumentException("mock-portal süreleri pozitif olmalı");
        }
    }

    public Optional<Pattern> failPattern() {
        return pattern(failInvoiceNoPattern);
    }

    public Optional<Pattern> slowPattern() {
        return pattern(slowInvoiceNoPattern);
    }

    private static Optional<Pattern> pattern(String regex) {
        return regex == null || regex.isBlank() ? Optional.empty() : Optional.of(Pattern.compile(regex));
    }

    @Override
    public String toString() {
        return "PortalProperties[username=" + username + ", password=***, pageSize=" + pageSize
                + ", failInvoiceNoPattern=" + failInvoiceNoPattern + ", slowInvoiceNoPattern=" + slowInvoiceNoPattern
                + ", slowResponseDelay=" + slowResponseDelay + ", sessionIdleTimeout=" + sessionIdleTimeout
                + ", errorRate=" + errorRate + ", errorSeed=" + errorSeed + ", elementDelay=" + elementDelay + "]";
    }
}
