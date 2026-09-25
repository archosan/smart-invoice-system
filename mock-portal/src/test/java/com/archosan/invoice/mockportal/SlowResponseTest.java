package com.archosan.invoice.mockportal;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.UUID;

import static com.archosan.invoice.mockportal.PortalClient.count;
import static com.archosan.invoice.mockportal.PortalClient.invoice;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yavaş yanıt (B-35): deseni tutan gönderim önce kaydedilir, yanıt sonra gecikir. Bot yanıtı beklerken ölürse portalda
 * kayıt vardır, sistemde yoktur (US-08); ön arama onu bulmalıdır.
 */
@TestPropertySource(properties = {
        "mock-portal.slow-invoice-no-pattern=^SLOW-",
        "mock-portal.slow-response-delay=1500ms"})
class SlowResponseTest extends PortalTest {

    @Test
    void matchingSubmissionIsStoredBeforeTheDelayedResponse() {
        String slow = "SLOW-" + UUID.randomUUID();
        PortalClient portal = loggedIn();

        long start = System.nanoTime();
        String refNo = portal.submitInvoice(invoice(slow));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(1500));
        assertThat(refNo).isNotBlank();
        assertThat(count(portal.get("/invoices?q=" + slow).body(), "class=\"invoice-row\"")).isEqualTo(1);

        // Desen tutmayan gönderim gecikmez.
        long other = System.nanoTime();
        portal.submitInvoice(invoice("FAST-" + UUID.randomUUID()));
        assertThat(Duration.ofNanos(System.nanoTime() - other)).isLessThan(Duration.ofMillis(1500));
    }
}
