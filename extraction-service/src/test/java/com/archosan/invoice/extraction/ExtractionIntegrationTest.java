package com.archosan.invoice.extraction;

import com.archosan.invoice.testsupport.AbstractIntegrationTest;
import com.archosan.invoice.testsupport.InfrastructureContainers;
import com.archosan.invoice.testsupport.ServiceDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * extraction-service entegrasyon testlerinin tabanı: extraction_db (extraction_user), Redis, B-16 seti depo kökü olarak.
 * Gerçek Ollama kullanılmaz; testler {@code ChatModel}'i stub'la veya Ollama'yı sahte bir HTTP sunucusuyla değiştirir.
 *
 * <p>Her test sınıfı kendi context'ini kapatır: her context'te aynı kuyruğu dinleyen bir container vardır;
 * önbellekte açık kalan başka bir context'in container'ı mesajları kapmasın.
 */
@DirtiesContext
public abstract class ExtractionIntegrationTest extends AbstractIntegrationTest {

    protected static final Path INVOICES = Path.of("src/test/resources/invoices").toAbsolutePath();

    @Autowired
    private JdbcClient cleanupJdbc;

    /** API kullanıcılarının şifreleri her çalıştırmada rastgele (B-47). */
    private static final Map<String, String> PASSWORDS = Map.of("expert", UUID.randomUUID().toString(),
            "approver", UUID.randomUUID().toString(), "admin", UUID.randomUUID().toString(),
            "expert-approver", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.registerPostgres(registry, ServiceDatabase.EXTRACTION);
        InfrastructureContainers.registerRedis(registry);
        registry.add("invoice.extraction.storage-dir", INVOICES::toString);
        PASSWORDS.forEach((user, password) ->
                registry.add("invoice.extraction.security.users." + user + ".password", () -> password));
    }

    /** {@code user} için HTTP Basic başlıkları (B-47 API testleri). */
    protected static Consumer<HttpHeaders> basicAuth(String user) {
        return headers -> headers.setBasicAuth(user, PASSWORDS.get(user));
    }

    @BeforeEach
    void cleanDatabase() {
        cleanupJdbc.sql("DELETE FROM extraction_runs").update();
        cleanupJdbc.sql("DELETE FROM outbox").update();
        cleanupJdbc.sql("DELETE FROM inbox").update();
    }
}
