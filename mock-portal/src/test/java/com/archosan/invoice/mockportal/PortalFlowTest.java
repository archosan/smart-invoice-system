package com.archosan.invoice.mockportal;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static com.archosan.invoice.mockportal.PortalClient.count;
import static com.archosan.invoice.mockportal.PortalClient.invoice;
import static org.assertj.core.api.Assertions.assertThat;

/** Login, giriş formu, kayıt numarası, sayfalı liste ve arama (FR-P1, FR-P2). */
class PortalFlowTest extends PortalTest {

    @Test
    void pagesRequireLogin() {
        for (String path : new String[] {"/invoices", "/invoices/new", "/invoices/1000"}) {
            HttpResponse<String> response = client.get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).as(path).hasValueSatisfying(
                    l -> assertThat(l).endsWith("/login"));
        }
        assertThat(client.post("/invoices", invoice("X-1")).statusCode()).isEqualTo(302);
    }

    @Test
    void wrongPasswordShowsErrorAndGrantsNoSession() {
        HttpResponse<String> response = client.login(USERNAME, "yanlis");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id=\"login-error\"");
        assertThat(client.get("/invoices").statusCode()).isEqualTo(302);
    }

    @Test
    void loginRedirectsToListAndLogoutEndsSession() {
        HttpResponse<String> response = client.login(USERNAME, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
                l -> assertThat(l).endsWith("/invoices"));
        assertThat(client.get("/invoices").statusCode()).isEqualTo(200);

        client.post("/logout", Map.of());

        assertThat(client.get("/invoices").statusCode()).isEqualTo(302);
    }

    @Test
    void submittedInvoiceGetsRefNoAndAppearsInSearch() {
        String invoiceNo = "ANK-" + UUID.randomUUID();
        PortalClient portal = loggedIn();

        String refNo = portal.submitInvoice(invoice(invoiceNo));

        HttpResponse<String> detail = portal.get("/invoices/" + refNo + "?created");
        assertThat(detail.body()).contains("id=\"success-message\"", invoiceNo, "4810293756", "5100.00",
                "Anadolu Rulman A.Ş.");
        String list = portal.get("/invoices?q=" + invoiceNo).body();
        assertThat(count(list, "class=\"invoice-row\"")).isEqualTo(1);
        assertThat(list).contains("data-ref-no=\"" + refNo + "\"");
    }

    @Test
    void portalIsNotIdempotent() {
        // Aynı fatura iki kez girilirse iki kayıt oluşur; çift kaydı önlemek RPA tarafının işi (NFR-01).
        String invoiceNo = "DUP-" + UUID.randomUUID();
        PortalClient portal = loggedIn();

        String first = portal.submitInvoice(invoice(invoiceNo));
        String second = portal.submitInvoice(invoice(invoiceNo));

        assertThat(first).isNotEqualTo(second);
        assertThat(count(portal.get("/invoices?q=" + invoiceNo).body(), "class=\"invoice-row\"")).isEqualTo(2);
    }

    @Test
    void searchResultsArePagedNewestFirst() {
        String prefix = "PG-" + UUID.randomUUID().toString().substring(0, 8);
        PortalClient portal = loggedIn();
        for (int i = 1; i <= 12; i++) {
            portal.submitInvoice(invoice(prefix + "-" + i));
        }

        String first = portal.get("/invoices?q=" + prefix).body();
        assertThat(count(first, "class=\"invoice-row\"")).isEqualTo(10);
        assertThat(first).contains("Sayfa 1 / 2", "id=\"next-page\"").doesNotContain("id=\"prev-page\"");
        assertThat(first.indexOf(prefix + "-12<")).isLessThan(first.indexOf(prefix + "-11<"));

        String second = portal.get("/invoices?q=" + prefix + "&page=1").body();
        assertThat(count(second, "class=\"invoice-row\"")).isEqualTo(2);
        assertThat(second).contains("Sayfa 2 / 2", "id=\"prev-page\"", prefix + "-1<", prefix + "-2<")
                .doesNotContain("id=\"next-page\"");

        // Aralık dışı sayfa son sayfaya çekilir.
        assertThat(portal.get("/invoices?q=" + prefix + "&page=99").body()).contains("Sayfa 2 / 2");
    }

    @Test
    void invalidFormIsShownAgainWithErrorsAndNothingIsStored() {
        String invoiceNo = "BAD-" + UUID.randomUUID();
        Map<String, String> form = invoice(invoiceNo);
        form.put("supplierVkn", "123");
        form.put("grandTotal", "5.100,00");
        form.put("dueDate", "");
        PortalClient portal = loggedIn();

        HttpResponse<String> response = portal.post("/invoices", form);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id=\"form-errors\"", "data-field=\"supplierVkn\"",
                "data-field=\"grandTotal\"", "data-field=\"dueDate\"", "value=\"" + invoiceNo + "\"");
        assertThat(count(portal.get("/invoices?q=" + invoiceNo).body(), "class=\"invoice-row\"")).isZero();
    }

    @Test
    void userInputIsEscaped() {
        Map<String, String> form = invoice("XSS-" + UUID.randomUUID());
        form.put("supplierName", "<script>alert(1)</script>");
        PortalClient portal = loggedIn();

        String refNo = portal.submitInvoice(form);

        assertThat(portal.get("/invoices/" + refNo).body())
                .doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    /** Hata enjeksiyonu varsayılanda kapalı (B-36): gecikme yok, sayfalar gizlenmez. */
    @Test
    void faultInjectionIsOffByDefault() {
        String form = loggedIn().get("/invoices/new").body();

        assertThat(form).doesNotContain("[data-delayed] { display: none; }", "setTimeout");
    }

    @Test
    void unknownRefNoIs404() {
        assertThat(loggedIn().get("/invoices/yok").statusCode()).isEqualTo(404);
    }
}
