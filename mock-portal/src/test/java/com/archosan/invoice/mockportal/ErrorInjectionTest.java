package com.archosan.invoice.mockportal;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Rastgele 500 (FR-P3, B-36), oran 1: her portal sayfası 500 döner; health etkilenmez (compose sağlık kontrolü). */
@TestPropertySource(properties = "mock-portal.error-rate=1")
class ErrorInjectionTest extends PortalTest {

    @Test
    void everyPortalPageFailsButHealthDoesNot() {
        for (String path : new String[] {"/login", "/invoices", "/invoices/new", "/"}) {
            HttpResponse<String> response = client.get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(500);
            assertThat(response.body()).as(path).contains("id=\"portal-error\"");
        }
        assertThat(client.login(USERNAME, PASSWORD).statusCode()).isEqualTo(500);
        assertThat(client.get("/actuator/health").statusCode()).isEqualTo(200);
    }
}
