package com.archosan.invoice.mockportal;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.util.UUID;

import static com.archosan.invoice.mockportal.PortalClient.count;
import static com.archosan.invoice.mockportal.PortalClient.invoice;
import static org.assertj.core.api.Assertions.assertThat;

/** Hata bayrağı: deseni tutan fatura her seferinde 500 alır, diğerleri girilir (B-24; B-28'in dayanağı). */
@TestPropertySource(properties = "mock-portal.fail-invoice-no-pattern=^FAIL-")
class FailurePatternTest extends PortalTest {

    @Test
    void matchingInvoiceAlwaysFailsWithoutBeingStoredOthersSucceed() {
        String failing = "FAIL-" + UUID.randomUUID();
        PortalClient portal = loggedIn();

        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> response = portal.post("/invoices", invoice(failing));
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("id=\"portal-error\"");
        }
        assertThat(count(portal.get("/invoices?q=" + failing).body(), "class=\"invoice-row\"")).isZero();

        // Desen başta aranır; numaranın ortasındaki "FAIL-" eşleşmez.
        assertThat(portal.submitInvoice(invoice("OK-FAIL-" + UUID.randomUUID()))).isNotBlank();
    }
}
