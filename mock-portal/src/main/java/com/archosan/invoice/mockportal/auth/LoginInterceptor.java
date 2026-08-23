package com.archosan.invoice.mockportal.auth;

import com.archosan.invoice.mockportal.PortalProperties;
import com.archosan.invoice.mockportal.fault.FaultInjector;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.time.Instant;

/**
 * Oturumu olmayan istek login sayfasına yönlendirilir; eski portallardaki gibi 401 yerine 302.
 *
 * <p>Oturum boşta kalma süresi ({@code mock-portal.session-idle-timeout}, FR-P3, B-36) burada, saniye hassasiyetiyle
 * uygulanır: son istekten bu yana süre aşıldıysa oturum düşürülür ve istek login'e yönlenir. Her geçerli istek süreyi
 * yeniler.
 *
 * <p>Rastgele 500 ({@link FaultInjector}) bu kontrolden önce, login sayfası dahil bütün portal sayfalarına uygulanır;
 * Actuator hariç tutulur ki compose konteyneri sağlıksız saymasın.
 */
@Configuration(proxyBeanMethods = false)
public class LoginInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    static final String USER_ATTRIBUTE = "portalUser";
    static final String LAST_ACCESS_ATTRIBUTE = "portalLastAccess";

    private static final Logger log = LoggerFactory.getLogger(LoginInterceptor.class);

    private final Duration idleTimeout;
    private final FaultInjector faults;

    public LoginInterceptor(PortalProperties properties, FaultInjector faults) {
        this.idleTimeout = properties.sessionIdleTimeout();
        this.faults = faults;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(USER_ATTRIBUTE) != null) {
            Instant last = (Instant) session.getAttribute(LAST_ACCESS_ATTRIBUTE);
            Instant now = Instant.now();
            if (last != null && Duration.between(last, now).compareTo(idleTimeout) > 0) {
                log.info("Oturum zaman aşımı ({} boşta), login'e yönlendiriliyor", idleTimeout);
                session.invalidate();
            } else {
                session.setAttribute(LAST_ACCESS_ATTRIBUTE, now);
                return true;
            }
        }
        response.sendRedirect(request.getContextPath() + "/login");
        return false;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(faults).addPathPatterns("/", "/login", "/logout", "/invoices", "/invoices/**");
        registry.addInterceptor(this).addPathPatterns("/invoices", "/invoices/**");
    }
}
