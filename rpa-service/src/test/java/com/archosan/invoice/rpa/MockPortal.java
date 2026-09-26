package com.archosan.invoice.rpa;

import com.archosan.invoice.mockportal.MockPortalApplication;
import com.archosan.invoice.mockportal.invoice.InvoiceForm;
import com.archosan.invoice.mockportal.invoice.InvoiceStore;
import com.archosan.invoice.mockportal.invoice.PortalInvoice;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Gerçek mock-portal, test JVM'inde rastgele portta; JVM başına bir kez açılır (B-25). Seçici sözleşmesi böylece
 * gerçek sayfalara karşı sınanır.
 *
 * <p>Portal rpa-service'in sınıf yolunda çalışır: rpa'nın {@code application.yml}'ı yüklenmesin diye yapılandırma
 * dosyası okunmaz, portalın ayarları burada verilir; rpa'nın DataSource / Rabbit / Flyway auto-config'leri portalda
 * kapalıdır (common-messaging bileşenleri {@code JdbcClient}'a bağlı olduğu için onlar da kurulmaz).
 */
public final class MockPortal {

    public static final String USERNAME = "rpa-bot";
    /** Her çalıştırmada rastgele; koda ve teste secret yazılmaz. */
    public static final String PASSWORD = UUID.randomUUID().toString();
    /** Bu önekle başlayan fatura numarasının girişi her seferinde 500 alır (mock-portal hata bayrağı). */
    public static final String FAILING_PREFIX = "FAIL-";
    /** Bütün rpa testlerinde fatura formu ve sonuç tablosu bu kadar geç görünür (B-36). */
    static final int ELEMENT_DELAY_MS = 400;

    private static ConfigurableApplicationContext context;

    private MockPortal() {
    }

    public static synchronized String url() {
        if (context == null) {
            context = new SpringApplicationBuilder(MockPortalApplication.class)
                    .properties(
                            "spring.config.name=mock-portal-in-rpa-tests",
                            "spring.application.name=mock-portal",
                            "spring.main.banner-mode=off",
                            "spring.autoconfigure.exclude="
                                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration,"
                                    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                            "server.port=0",
                            "server.servlet.session.tracking-modes=cookie",
                            "mock-portal.username=" + USERNAME,
                            "mock-portal.password=" + PASSWORD,
                            "mock-portal.fail-invoice-no-pattern=^" + FAILING_PREFIX,
                            // Form ve sonuç tablosu sonradan görünür (FR-P3, B-36): bot görünür olmayı beklemeli.
                            "mock-portal.element-delay=" + ELEMENT_DELAY_MS + "ms")
                    .run();
            Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        }
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    /** Portala doğrudan kayıt ekler (bot dışında girilmiş ya da botun yarıda bıraktığı kayıt, B-35); kayıt no döner. */
    public static String enter(String supplierVkn, String invoiceNo) {
        url();
        return context.getBean(InvoiceStore.class).add(new InvoiceForm.Valid(supplierVkn, "Anadolu Rulman A.Ş.",
                invoiceNo, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), new BigDecimal("5100.00"),
                new BigDecimal("850.00"), "TRY")).refNo();
    }

    /** Portalda bu fatura numarasıyla girilmiş kayıtlar (tam eşleşme). */
    public static List<PortalInvoice> invoices(String invoiceNo) {
        url();
        return context.getBean(InvoiceStore.class).page(invoiceNo, 0, 100).items().stream()
                .filter(i -> i.invoiceNo().equals(invoiceNo))
                .toList();
    }
}
