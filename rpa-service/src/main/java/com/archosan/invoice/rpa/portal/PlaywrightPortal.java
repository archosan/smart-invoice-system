package com.archosan.invoice.rpa.portal;

import com.archosan.invoice.rpa.RpaProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Portala Playwright ile fatura girer (FR-R1, FR-R2): login, ön arama, form, kaydet, kayıt numarasını oku. Seçiciler
 * ve yollar konfigürasyondadır (NFR-09).
 *
 * <p><b>Ön arama (B-35, FR-R3):</b> her girişten önce fatura numarasıyla aranır, sonuç sayfaları {@code #next-page}
 * bitene kadar dolaşılır; fatura no ve VKN'si tam eşleşen satır varsa form doldurulmaz, o kaydın numarası döner
 * ({@link FoundExisting}). Portal idempotent olmadığı için "en fazla bir kez"in son katmanıdır: bot formu gönderip
 * kayıt numarasını okumadan ölürse (US-08) ya da kilit bir duraklamada el değiştirirse ikinci giriş burada önlenir.
 *
 * <p>Playwright thread-safe değildir; her giriş kendi Playwright'ını ve tarayıcısını açıp kapatır (B-25). Tarayıcı
 * açılışı fatura başına ~1-2 sn'dir; v1'de zaten her faturada login yapılır (oturum paylaşımı v2, FR-R4).
 *
 * <p>Süre: her adım {@code timeout} ile, girişin tamamı {@code submitTimeout} ile sınırlıdır; her adım ikisinden
 * kalanı kadar bekler. Böylece girişin üst sınırı bellidir ve listener kapanırken onu sonuna kadar bekleyebilir
 * (B-27).
 *
 * <p><b>Oturum (B-37, FR-R4):</b> bir sayfaya gidince ya da form gönderince login sayfasına düşülürse oturum düşmüştür
 * (portalın boşta kalma süresi). Bir kez yeniden login olunur ve akış <b>ön aramadan</b> baştan yapılır: oturum form
 * kaydedildikten sonra düşmüşse arama kaydı bulur, form ikinci kez girilmez. Oturum paylaşılmaz: her giriş kendi
 * tarayıcısıyla login olur.
 *
 * <p>Hatalar: her başarısız deneme {@link PortalAttemptFailedException} ile sarılır: düştüğü adım ve sayfanın ekran
 * görüntüsü (FR-R6). Asıl hata: portalın form doğrulaması {@link PortalRejectedException} (kalıcı); hata sayfası,
 * düşen oturum ve süre bütçesinin bitmesi {@link PortalErrorException}, adım zaman aşımı ve bulunamayan eleman
 * {@code PlaywrightException} (geçici). Hata sayfası her sayfa geçişinden sonra hemen fark edilir.
 */
@Component
public class PlaywrightPortal {

    /** Arama formu gönderildi: sonuç sayfasının adresinde sorgu var ya da oturum düştü (login). */
    private static final Pattern SEARCH_SUBMITTED = Pattern.compile(".*([?&]q=|/login).*");

    private static final Logger log = LoggerFactory.getLogger(PlaywrightPortal.class);

    private final RpaProperties.Portal portal;
    private final RpaProperties.Selectors s;

    public PlaywrightPortal(RpaProperties properties) {
        this.portal = properties.portal();
        this.s = portal.selectors();
    }

    /** Girişin sonucu. */
    public sealed interface Outcome {

        String portalRefNo();
    }

    /** Fatura girildi; portalın verdiği kayıt numarası. */
    public record Entered(String portalRefNo) implements Outcome {
    }

    /** Fatura portalda zaten vardı (ön arama); form doldurulmadı. */
    public record FoundExisting(String portalRefNo) implements Outcome {
    }

    /** Arama sonuçlarında dolaşılacak en fazla sayfa; süre bütçesi ayrıca sınırlar. */
    private static final int MAX_SEARCH_PAGES = 100;

    public Outcome submit(PortalValues invoice) {
        return submit(invoice, page -> { });
    }

    /** @param afterLogin ilk login'den hemen sonra sayfayla yapılacak iş; testler oturumu düşürmek için kullanır */
    Outcome submit(PortalValues invoice, Consumer<Page> afterLogin) {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(portal.headless())
                     .setTimeout(portal.timeout().toMillis()))) {
            Session session = new Session(browser.newPage(), Instant.now().plus(portal.submitTimeout()));
            try {
                login(session);
                afterLogin.accept(session.page);
                return withRelogin(session, () -> {
                    Optional<String> existing = findExisting(session, invoice);
                    if (existing.isPresent()) {
                        return new FoundExisting(existing.get());
                    }
                    return new Entered(enter(session, invoice));
                });
            } catch (RuntimeException e) {
                throw new PortalAttemptFailedException(session.step, session.screenshot(), e);
            }
        }
    }

    /** Oturum düşerse bir kez yeniden login olup akışı (ön aramadan) baştan yapar (FR-R4). */
    private <T> T withRelogin(Session session, Supplier<T> phase) {
        try {
            return phase.get();
        } catch (SessionExpired first) {
            log.info("Portal oturumu düştü ({}), yeniden login (FR-R4)", first.getMessage());
            login(session);
            try {
                return phase.get();
            } catch (SessionExpired again) {
                throw new PortalErrorException(
                        "Portal oturumu yeniden login'den sonra da düştü: " + again.getMessage());
            }
        }
    }

    /** Oturum düştü: login sayfasına yönlendirildik. */
    private static final class SessionExpired extends RuntimeException {

        SessionExpired(String step) {
            super(step, null, false, false);
        }
    }

    private void login(Session page) {
        page.step("login");
        page.navigate(portal.paths().login());
        page.fill(s.username(), portal.username());
        page.fill(s.password(), portal.password());
        page.click(s.loginSubmit());
        page.awaitAny(s.loggedIn(), s.loginError(), s.portalError());
        page.failOnErrorPage();
        if (page.has(s.loginError())) {
            // Yanlış ayar; tekrar denemek düzeltmez ama mesaj geçici sayılır ki ayar düzelince akış sürsün.
            throw new PortalErrorException("Portal girişi reddedildi (PORTAL_USERNAME / PORTAL_PASSWORD?)");
        }
    }

    /** Fatura no ile arar, sonuç sayfalarını dolaşır; fatura no ve VKN'si tam eşleşen ilk satırın kayıt numarası. */
    private Optional<String> findExisting(Session page, PortalValues invoice) {
        page.step("ön arama");
        page.open(portal.paths().search());
        page.fill(s.searchInvoiceNo(), invoice.invoiceNo());
        page.click(s.searchSubmit());
        page.awaitUrl(SEARCH_SUBMITTED);
        page.checkArrived();
        for (int pageNo = 1; pageNo <= MAX_SEARCH_PAGES; pageNo++) {
            page.awaitAny(s.resultTable());
            for (Locator row : page.all(s.resultRow())) {
                if (invoice.invoiceNo().equals(page.text(row, s.rowInvoiceNo()))
                        && invoice.supplierVkn().equals(page.text(row, s.rowSupplierVkn()))) {
                    return Optional.ofNullable(row.getAttribute(s.rowRefNoAttribute()));
                }
            }
            if (!page.has(s.nextPage())) {
                return Optional.empty();
            }
            page.open(page.attribute(s.nextPage(), "href"));
        }
        throw new PortalErrorException("Ön arama " + MAX_SEARCH_PAGES + " sayfayı aştı: " + invoice.invoiceNo());
    }

    private String enter(Session page, PortalValues invoice) {
        page.step("form açma");
        page.open(portal.paths().newInvoice());
        page.awaitAny(s.invoiceForm());
        page.step("form doldurma");
        page.fill(s.supplierVkn(), invoice.supplierVkn());
        page.fill(s.supplierName(), invoice.supplierName());
        page.fill(s.invoiceNo(), invoice.invoiceNo());
        page.fill(s.invoiceDate(), invoice.invoiceDate());
        page.fill(s.dueDate(), invoice.dueDate());
        page.fill(s.grandTotal(), invoice.grandTotal());
        page.fill(s.vatTotal(), invoice.vatTotal());
        page.select(s.currency(), invoice.currency());
        page.step("gönderme");
        page.click(s.invoiceSubmit());

        // Sonuçlardan biri: kayıt sayfası, form hataları, hata sayfası ya da (oturum düştüyse) login sayfası.
        page.step("sonucu okuma");
        page.awaitAny(s.refNo(), s.formErrors(), s.portalError(), s.loginSubmit());
        page.checkArrived();
        if (page.has(s.formErrors())) {
            throw new PortalRejectedException("Portal formu reddetti: " + page.fieldErrors(s.fieldError()));
        }
        String refNo = page.text(s.refNo());
        if (refNo.isEmpty()) {
            throw new PortalErrorException("Kayıt sayfasında kayıt numarası boş");
        }
        return refNo;
    }

    /** Bir girişin sayfası; her adımdan önce zaman aşımını kalan bütçeye göre ayarlar. */
    private final class Session {

        private final Page page;
        private final Instant deadline;
        private String step = "tarayıcı";

        Session(Page page, Instant deadline) {
            this.page = page;
            this.deadline = deadline;
        }

        void step(String name) {
            this.step = name;
        }

        /** Login dışındaki bir sayfaya gider; hata sayfasına ya da (oturum düştüyse) login'e düşmeyi fark eder. */
        void open(String path) {
            navigate(path);
            checkArrived();
        }

        void navigate(String path) {
            budget();
            page.navigate(portal.resolve(path));
        }

        /** Hata sayfası → geçici hata; login sayfası → oturum düştü. */
        void checkArrived() {
            failOnErrorPage();
            if (has(s.loginSubmit()) && !has(s.loggedIn())) {
                throw new SessionExpired(step);
            }
        }

        void failOnErrorPage() {
            if (has(s.portalError())) {
                throw new PortalErrorException("Portal hata sayfası döndü (" + step + ")");
            }
        }

        /** O anki sayfanın tam ekran görüntüsü; çekilemezse {@code null} (FR-R6). */
        byte[] screenshot() {
            try {
                return page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setTimeout(5000));
            } catch (RuntimeException e) {
                log.warn("Ekran görüntüsü alınamadı: {}", e.toString());
                return null;
            }
        }

        void fill(String selector, String value) {
            budget();
            page.fill(selector, value);
        }

        void select(String selector, String value) {
            budget();
            page.selectOption(selector, value);
        }

        void click(String selector) {
            budget();
            page.click(selector);
        }

        void awaitAny(String... selectors) {
            budget();
            page.locator(String.join(", ", selectors)).first().waitFor();
        }

        boolean has(String selector) {
            return page.locator(selector).count() > 0;
        }

        String text(String selector) {
            budget();
            return page.locator(selector).innerText().strip();
        }

        String text(Locator within, String selector) {
            budget();
            return within.locator(selector).innerText().strip();
        }

        String attribute(String selector, String name) {
            budget();
            return page.locator(selector).getAttribute(name);
        }

        List<Locator> all(String selector) {
            return page.locator(selector).all();
        }

        void awaitUrl(Pattern url) {
            budget();
            page.waitForURL(url);
        }

        String fieldErrors(String selector) {
            return page.locator(selector).all().stream()
                    .map(Session::describe)
                    .collect(Collectors.joining(", "));
        }

        private void budget() {
            Duration left = Duration.between(Instant.now(), deadline);
            if (left.isNegative() || left.isZero()) {
                throw new PortalErrorException("Portal girişi süre bütçesini aştı (" + portal.submitTimeout() + ")");
            }
            long step = Math.min(portal.timeout().toMillis(), left.toMillis());
            page.setDefaultTimeout(step);
            page.setDefaultNavigationTimeout(step);
        }

        private static String describe(Locator error) {
            String field = error.getAttribute("data-field");
            return (field == null ? "" : field + ": ") + error.innerText().strip();
        }
    }
}
