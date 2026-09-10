package com.archosan.invoice.compliance;

import com.archosan.invoice.testsupport.AbstractIntegrationTest;
import com.archosan.invoice.testsupport.InfrastructureContainers;
import com.archosan.invoice.testsupport.ServiceDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * compliance-service entegrasyon testlerinin tabanı: compliance_db (compliance_user), Redis (LLM semaforu), geçici
 * sözleşme dizini, stub embedding ve sohbet modelleri ({@link StubEmbeddingModel}, {@link StubChatModel}; gerçek
 * Ollama yok) ve her çalıştırmada rastgele API şifreleri (B-45, B-46).
 *
 * <p>Her test sınıfı kendi context'ini kapatır: her context'te aynı kuyruğu dinleyen bir container vardır;
 * önbellekte açık kalan başka bir context'in container'ı mesajları kapmasın.
 */
@DirtiesContext
@Import(ComplianceIntegrationTest.StubEmbeddingConfiguration.class)
public abstract class ComplianceIntegrationTest extends AbstractIntegrationTest {

    protected static final Path STORAGE_DIR = createStorageDir();
    protected static final StubEmbeddingModel EMBEDDINGS = new StubEmbeddingModel();
    protected static final StubChatModel LLM = new StubChatModel();

    protected static final String EXPERT = "expert";
    protected static final String APPROVER = "approver";
    private static final Map<String, String> PASSWORDS = Map.of(EXPERT, random(), APPROVER, random(),
            "admin", random(), "expert-approver", random());

    @TestConfiguration(proxyBeanMethods = false)
    static class StubEmbeddingConfiguration {

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return EMBEDDINGS;
        }

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return LLM;
        }
    }

    @Autowired
    private JdbcClient cleanupJdbc;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        InfrastructureContainers.registerPostgres(registry, ServiceDatabase.COMPLIANCE);
        InfrastructureContainers.registerRedis(registry);
        registry.add("invoice.compliance.storage-dir", STORAGE_DIR::toString);
        PASSWORDS.forEach((user, password) ->
                registry.add("invoice.compliance.security.users." + user + ".password", () -> password));
    }

    @BeforeEach
    void cleanDatabase() {
        // Tek komut: önceki testten arka planda süren bir indeksleme iki DELETE arasına chunk yazamasın.
        cleanupJdbc.sql("TRUNCATE compliance_checks, contract_chunks, contracts, outbox, inbox").update();
        EMBEDDINGS.reset();
        LLM.reset();
    }

    protected static Consumer<HttpHeaders> basicAuth(String user) {
        return headers -> headers.setBasicAuth(user, PASSWORDS.get(user));
    }

    private static String random() {
        return UUID.randomUUID().toString();
    }

    private static Path createStorageDir() {
        try {
            return Files.createTempDirectory("contracts-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
