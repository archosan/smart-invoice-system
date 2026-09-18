package com.archosan.invoice.document;

import com.archosan.invoice.testsupport.AbstractIntegrationTest;
import com.archosan.invoice.testsupport.InfrastructureContainers;
import com.archosan.invoice.testsupport.ServiceDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * document-service entegrasyon testlerinin tabanı: document_db'ye document_user ile bağlanır, PDF'leri geçici bir
 * dizinde saklar. Konteynerler testler arasında paylaşıldığı için her testten önce tüm tablolar foreign key sırasıyla
 * boşaltılır ({@code settings} hariç). {@code status_transitions} yalnız eklenir (B-42, V5): servis kullanıcısı onu
 * silemez, temizlik superuser bağlantısıyla ve tetikleyiciler atlanarak yapılır. API kullanıcılarının (B-43)
 * şifreleri her çalıştırmada rastgeledir; testler {@link #basicAuth} ile istek yapar.
 */
public abstract class DocumentIntegrationTest extends AbstractIntegrationTest {

    protected static final Path STORAGE_DIR = createStorageDir();

    protected static final String EXPERT = "expert";
    protected static final String APPROVER = "approver";
    protected static final String ADMIN = "admin";
    protected static final String EXPERT_APPROVER = "expert-approver";

    private static final Map<String, String> PASSWORDS = Map.of(EXPERT, randomPassword(), APPROVER, randomPassword(),
            ADMIN, randomPassword(), EXPERT_APPROVER, randomPassword());

    private static final List<String> TABLES_IN_DELETE_ORDER = List.of("outbox", "inbox", "dead_letters",
            "compliance_results", "invoice_data", "status_transitions", "documents");

    @BeforeEach
    void cleanDatabase() throws SQLException {
        try (Connection connection = InfrastructureContainers.superuserConnection(ServiceDatabase.DOCUMENT);
             Statement statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            for (String table : TABLES_IN_DELETE_ORDER) {
                statement.execute("DELETE FROM " + table);
            }
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        InfrastructureContainers.registerPostgres(registry, ServiceDatabase.DOCUMENT);
        registry.add("invoice.document.storage-dir", STORAGE_DIR::toString);
        PASSWORDS.forEach((user, password) ->
                registry.add("invoice.document.security.users." + user + ".password", () -> password));
    }

    /** {@code user} için HTTP Basic başlıkları; RestClient'ın {@code defaultHeaders}'ına verilir. */
    protected static Consumer<HttpHeaders> basicAuth(String user) {
        return headers -> headers.setBasicAuth(user, PASSWORDS.get(user));
    }

    private static String randomPassword() {
        return UUID.randomUUID().toString();
    }

    private static Path createStorageDir() {
        try {
            return Files.createTempDirectory("documents-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
