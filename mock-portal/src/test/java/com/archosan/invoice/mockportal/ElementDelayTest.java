package com.archosan.invoice.mockportal;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gecikmeli eleman (FR-P3, B-36): fatura formu ve sonuç tablosu gizli gelir, verilen süre sonra görünür. Görünür olma
 * tarayıcıda (JavaScript) olur; burada sayfanın bunu ilan ettiği doğrulanır, bot tarafı rpa testlerinde.
 */
@TestPropertySource(properties = "mock-portal.element-delay=2500ms")
class ElementDelayTest extends PortalTest {

    @Test
    void formAndResultTableAreRevealedAfterTheDelay() {
        PortalClient portal = loggedIn();

        String form = portal.get("/invoices/new").body();
        String list = portal.get("/invoices").body();

        assertThat(form).contains("<form id=\"invoice-form\"", "data-delayed", "[data-delayed] { display: none; }",
                "2500");
        assertThat(list).contains("<table id=\"invoice-table\" data-delayed", "2500");
    }
}
