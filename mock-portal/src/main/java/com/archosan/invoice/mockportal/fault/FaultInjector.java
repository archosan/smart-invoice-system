package com.archosan.invoice.mockportal.fault;

import com.archosan.invoice.mockportal.PortalProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Rastgele 500 (FR-P3, B-36): her portal sayfası isteği {@code mock-portal.error-rate} olasılıkla, sayfayı hiç
 * işlemeden {@code #portal-error} sayfasıyla 500 döner. Tohum ({@code mock-portal.error-seed}) verilirse dizi
 * tekrarlanabilir; verilmezse her çalıştırmada farklıdır. Hangi sayfalara uygulandığı
 * {@link com.archosan.invoice.mockportal.auth.LoginInterceptor}'da.
 */
@Component
public class FaultInjector implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    static final String ERROR_PAGE = """
            <!DOCTYPE html>
            <html lang="tr"><head><meta charset="UTF-8"><title>Hata · Muhasebe Portalı</title></head>
            <body><main><h1 id="portal-error">Sunucu hatası</h1>
            <p>İşleminiz tamamlanamadı. Lütfen daha sonra tekrar deneyin.</p></main></body></html>
            """;

    private final double rate;
    private final RandomGenerator random;

    public FaultInjector(PortalProperties properties) {
        this.rate = properties.errorRate();
        this.random = properties.errorSeed() == null ? new Random() : new Random(properties.errorSeed());
    }

    /** Bu istek hata versin mi; tohumlu dizide sıra önemli olduğu için tek tek. */
    synchronized boolean shouldFail() {
        return rate > 0 && random.nextDouble() < rate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!shouldFail()) {
            return true;
        }
        log.warn("Hata enjeksiyonu: {} {} → 500", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("text/html;charset=UTF-8");
        response.getOutputStream().write(ERROR_PAGE.getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
