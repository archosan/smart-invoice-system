package com.archosan.invoice.rpa.portal;

import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B-27: girişin toplam süresi {@code submitTimeout} ile sınırlıdır; adım zaman aşımı daha uzun olsa da adım kalan
 * bütçe kadar bekler. Kapanışta beklenecek süre bu sınırdan türetildiği için sınır gerçekten tutmalıdır.
 */
class PlaywrightPortalBudgetTest {

    @Test
    void stepWaitsOnlyForRemainingBudget() {
        RpaProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "invoice.rpa.portal.url", MockPortal.url(),
                "invoice.rpa.portal.username", MockPortal.USERNAME,
                "invoice.rpa.portal.password", MockPortal.PASSWORD,
                "invoice.rpa.portal.timeout", "30s",
                "invoice.rpa.portal.submit-timeout", "3s",
                // Login düğmesi yok: adım ya 30 sn'lik adım süresini ya da kalan bütçeyi bekler.
                "invoice.rpa.portal.selectors.login-submit", "#yok")))
                .bind("invoice.rpa", RpaProperties.class).get();
        PortalValues invoice = PortalValues.of(new PostToPortal(UUID.randomUUID(), "4810293756",
                "Anadolu Rulman A.Ş.", "BUDGET-" + UUID.randomUUID(), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 1), new BigDecimal("5100.00"), new BigDecimal("850.00"), "TRY"));

        long start = System.nanoTime();
        Throwable failure = catchThrowable(() -> new PlaywrightPortal(properties).submit(invoice));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(failure).isNotNull();
        // Tarayıcı açılışı + 3 sn bütçe; 30 sn'lik adım süresine yaklaşmamalı.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));
    }
}
