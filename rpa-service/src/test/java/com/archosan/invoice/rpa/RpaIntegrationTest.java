package com.archosan.invoice.rpa;

import com.archosan.invoice.testsupport.AbstractIntegrationTest;
import com.archosan.invoice.testsupport.InfrastructureContainers;
import com.archosan.invoice.testsupport.ServiceDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * rpa-service entegrasyon testlerinin tabanı: rpa_db (rpa_user), Redis (fatura kilidi, B-34) ve JVM içi gerçek
 * mock-portal ({@link MockPortal}).
 * Tarayıcı gerçek headless Chromium'dur (Playwright); yerelde ilk çalıştırmada indirilebilir.
 *
 * <p>Her test sınıfı kendi context'ini kapatır: her context'te aynı kuyruğu dinleyen bir container vardır;
 * önbellekte açık kalan başka bir context'in container'ı mesajları kapmasın.
 */
@DirtiesContext
public abstract class RpaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcClient cleanupJdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.registerPostgres(registry, ServiceDatabase.RPA);
        InfrastructureContainers.registerRedis(registry);
        registry.add("invoice.rpa.portal.url", MockPortal::url);
        registry.add("invoice.rpa.portal.username", () -> MockPortal.USERNAME);
        registry.add("invoice.rpa.portal.password", () -> MockPortal.PASSWORD);
        registry.add("invoice.rpa.screenshot-dir", SCREENSHOTS::toString);
    }

    /** Başarısız denemelerin ekran görüntüleri (B-37); JVM başına bir geçici dizin. */
    protected static final Path SCREENSHOTS = temporaryDirectory();

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("rpa-screenshots");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        cleanupJdbc.sql("DELETE FROM portal_submissions").update();
        cleanupJdbc.sql("DELETE FROM rpa_attempts").update();
        cleanupJdbc.sql("DELETE FROM outbox").update();
        cleanupJdbc.sql("DELETE FROM inbox").update();
    }
}
