package com.archosan.invoice.rpa.portal;

import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaProperties;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * NFR-10, B-26: Playwright hata mesajına adımın "call log"unu ekler. Şifre alanı doldurulduktan sonra (ve
 * doldurulurken) başarısız olan girişin istisnasında — mesaj, neden zinciri ve yığın izi — şifre geçmemeli.
 */
class PlaywrightCredentialLeakTest {

    private static final PortalValues INVOICE = PortalValues.of(new PostToPortal(
            UUID.randomUUID(), "4810293756", "Anadolu Rulman A.Ş.", "LEAK-" + UUID.randomUUID(),
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), new BigDecimal("5100.00"),
            new BigDecimal("850.00"), "TRY"));

    @Test
    void failureAfterPasswordIsFilledDoesNotExposeIt() {
        Throwable failure = submitWith(Map.of("invoice.rpa.portal.selectors.login-submit", "#yok"));

        assertThat(failure).isInstanceOf(PortalAttemptFailedException.class).cause()
                .isInstanceOf(PlaywrightException.class);
        // Call log gerçekten mesajda: kontrol boşa geçmiyor.
        assertThat(everything(failure)).contains("#yok").doesNotContain(MockPortal.PASSWORD);
    }

    @Test
    void failureWhileFillingPasswordDoesNotExposeIt() {
        Throwable failure = submitWith(Map.of("invoice.rpa.portal.selectors.password", "#yok"));

        assertThat(failure).isInstanceOf(PortalAttemptFailedException.class).cause()
                .isInstanceOf(PlaywrightException.class);
        assertThat(everything(failure)).contains("#yok").doesNotContain(MockPortal.PASSWORD);
    }

    @Test
    void rejectedLoginDoesNotExposePassword() {
        Throwable failure = submitWith(Map.of("invoice.rpa.portal.password", "yanlis-" + MockPortal.PASSWORD));

        assertThat(failure).isInstanceOf(PortalAttemptFailedException.class).cause()
                .isInstanceOf(PortalErrorException.class);
        assertThat(everything(failure)).doesNotContain(MockPortal.PASSWORD);
    }

    private static Throwable submitWith(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>(Map.of(
                "invoice.rpa.portal.url", MockPortal.url(),
                "invoice.rpa.portal.username", MockPortal.USERNAME,
                "invoice.rpa.portal.password", MockPortal.PASSWORD,
                "invoice.rpa.portal.timeout", "2s"));
        values.putAll(overrides);
        RpaProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("invoice.rpa", RpaProperties.class).get();
        return catchThrowable(() -> new PlaywrightPortal(properties).submit(INVOICE));
    }

    private static String everything(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }
}
