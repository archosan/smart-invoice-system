package com.archosan.invoice.rpa;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * rpa-service ayarları ({@code invoice.rpa.*}).
 *
 * @param portal    portal adresi, kimlik bilgileri, süreler ve seçiciler
 * @param lockLease fatura başına Redis kilidinin kirası (B-34, FR-R7); watchdog üçte birinde yeniler
 * @param maxAttempts bir faturanın portal denemesi üst sınırı (B-37, FR-R5); geçici hatada mesaj bekleme odasında 30 sn
 *                  bekleyip yeniden denenir, sınır dolunca DLQ → {@code RPA_FAILED}
 * @param screenshotDir başarısız denemelerin ekran görüntüleri (B-37, FR-R6); {@code {dir}/{documentId}/…png}
 */
@ConfigurationProperties("invoice.rpa")
public record RpaProperties(
        @DefaultValue Portal portal,
        @DefaultValue("60s") Duration lockLease,
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("/data/screenshots") Path screenshotDir) {

    public RpaProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("invoice.rpa.max-attempts en az 1 olmalı: " + maxAttempts);
        }
    }

    /**
     * @param url       portalın kök adresi ({@code PORTAL_URL})
     * @param username  portal kullanıcısı ({@code PORTAL_USERNAME})
     * @param password  şifresi ({@code PORTAL_PASSWORD}); loga ve hata mesajına yazılmaz
     * @param timeout   tek bir sayfa adımının (gezinme, eleman bekleme) ve tarayıcı açılışının üst süresi
     * @param submitTimeout bir girişin (login'den kayıt numarasına) toplam üst süresi; adımlar kalan süreyle
     *                  sınırlanır. Kapanışta beklenecek süre buradan türetilir ({@link #maxProcessingTime()})
     * @param headless  tarayıcı penceresiz mi; yerelde hata ayıklarken kapatılabilir
     * @param paths     portal içindeki yollar
     * @param selectors seçici sözleşmesi (DECISIONS.md §4.5, NFR-09)
     */
    public record Portal(
            @DefaultValue("http://localhost:8090") URI url,
            String username,
            String password,
            @DefaultValue("15s") Duration timeout,
            @DefaultValue("60s") Duration submitTimeout,
            @DefaultValue("true") boolean headless,
            @DefaultValue Paths paths,
            @DefaultValue Selectors selectors) {

        public Portal {
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalArgumentException(
                        "invoice.rpa.portal.username ve password gerekli (PORTAL_USERNAME, PORTAL_PASSWORD)");
            }
        }

        /**
         * Bir mesajın portal işinin üst sınırı: tarayıcı açılışı ({@code timeout}) + giriş ({@code submitTimeout}) +
         * kapanış payı. Listener kapanırken sürmekte olan girişi en az bu kadar bekler; daha kısa beklerse mesaj
         * bırakılır, yedek örnek aynı faturayı girerken bu örnek de girmeye devam edebilir (FR-R8, B-27).
         */
        public Duration maxProcessingTime() {
            return timeout.plus(submitTimeout).plus(CLOSE_MARGIN);
        }

        private static final Duration CLOSE_MARGIN = Duration.ofSeconds(15);

        /** Kök adres + yol; kökte sondaki {@code /} tekrarlanmaz. */
        public String resolve(String path) {
            String base = url.toString();
            return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
        }

        @Override
        public String toString() {
            return "Portal[url=" + url + ", username=" + username + ", password=***, timeout=" + timeout
                    + ", submitTimeout=" + submitTimeout + ", headless=" + headless + "]";
        }
    }

    /**
     * @param search fatura listesi ve arama sayfası; ön arama burada yapılır (B-35)
     */
    public record Paths(
            @DefaultValue("/login") String login,
            @DefaultValue("/invoices/new") String newInvoice,
            @DefaultValue("/invoices") String search) {
    }

    public record Selectors(
            @DefaultValue("#username") String username,
            @DefaultValue("#password") String password,
            @DefaultValue("#login-submit") String loginSubmit,
            @DefaultValue("#login-error") String loginError,
            @DefaultValue("#nav-invoices") String loggedIn,
            @DefaultValue("#invoice-form") String invoiceForm,
            @DefaultValue("#supplierVkn") String supplierVkn,
            @DefaultValue("#supplierName") String supplierName,
            @DefaultValue("#invoiceNo") String invoiceNo,
            @DefaultValue("#invoiceDate") String invoiceDate,
            @DefaultValue("#dueDate") String dueDate,
            @DefaultValue("#grandTotal") String grandTotal,
            @DefaultValue("#vatTotal") String vatTotal,
            @DefaultValue("#currency") String currency,
            @DefaultValue("#invoice-submit") String invoiceSubmit,
            @DefaultValue("#form-errors") String formErrors,
            @DefaultValue(".field-error") String fieldError,
            @DefaultValue("#ref-no") String refNo,
            @DefaultValue("#portal-error") String portalError,
            @DefaultValue("#search-invoice-no") String searchInvoiceNo,
            @DefaultValue("#search-submit") String searchSubmit,
            @DefaultValue("#invoice-table") String resultTable,
            @DefaultValue("tr.invoice-row") String resultRow,
            @DefaultValue(".invoice-no") String rowInvoiceNo,
            @DefaultValue(".supplier-vkn") String rowSupplierVkn,
            @DefaultValue("data-ref-no") String rowRefNoAttribute,
            @DefaultValue("#next-page") String nextPage) {
    }
}
