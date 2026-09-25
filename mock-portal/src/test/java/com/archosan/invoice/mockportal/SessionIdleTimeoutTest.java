package com.archosan.invoice.mockportal;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Oturum zaman aşımı (FR-P3, B-36): boşta kalan oturum düşer; her geçerli istek süreyi yeniler. */
@TestPropertySource(properties = "mock-portal.session-idle-timeout=1500ms")
class SessionIdleTimeoutTest extends PortalTest {

    @Test
    void idleSessionExpiresAndActivityKeepsItAlive() throws Exception {
        PortalClient portal = loggedIn();
        for (int i = 0; i < 3; i++) {
            Thread.sleep(800);
            assertThat(portal.get("/invoices").statusCode()).as("istek %d süreyi yeniler", i).isEqualTo(200);
        }

        Thread.sleep(2000);

        assertThat(portal.get("/invoices").statusCode()).isEqualTo(302);
        // Oturum düşürüldü: yeniden giriş gerekir.
        assertThat(portal.get("/invoices").headers().firstValue("Location")).hasValueSatisfying(
                l -> assertThat(l).endsWith("/login"));
        assertThat(portal.login(USERNAME, PASSWORD).statusCode()).isEqualTo(302);
        assertThat(portal.get("/invoices").statusCode()).isEqualTo(200);
    }
}
