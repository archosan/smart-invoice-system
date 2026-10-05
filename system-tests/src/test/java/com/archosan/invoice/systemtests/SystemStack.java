package com.archosan.invoice.systemtests;

import com.archosan.invoice.compliance.testdata.SyntheticContracts;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tam yığın (B-28): gerçek {@code docker-compose.yml} + {@code compose.system-tests.yml}, ayrı proje adıyla
 * ({@value #PROJECT}). JVM başına bir kez kurulur, JVM kapanırken volume'larıyla kaldırılır ({@code
 * -Dsystem-tests.keep=true} ile açık bırakılır; loglar her durumda {@code target/compose.log}'a yazılır).
 *
 * <p>Şifreler her çalıştırmada rastgele üretilir ve compose'a süreç ortamıyla verilir; geliştiricinin {@code .env}'i
 * okunmaz (boş bir env dosyası verilir). Ollama test JVM'indeki {@link FakeLlm}'dir; konteynerler ona
 * {@code host.docker.internal} ile ulaşır. Portal, numarası {@value #FAILING_PREFIX} ile başlayan faturada hep 500
 * döner; {@value #SLOW_PREFIX} ile başlayanı kaydeder ama yanıtı 20 sn geciktirir (B-35).
 */
final class SystemStack {

    static final String PROJECT = "invoice-system-it";
    static final String FAILING_PREFIX = "FAIL-";
    static final String SLOW_PREFIX = "SLOW-";
    private static final String RABBITMQ_USERNAME = "invoice";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static SystemStack instance;

    private final Path root;
    private final Path emptyEnvFile;
    private final Map<String, String> env = new HashMap<>();
    private final FakeLlm llm = new FakeLlm();
    private DocumentApi documents;
    private ContractApi contracts;
    private int postgresPort;
    private CachingConnectionFactory rabbitConnections;

    static synchronized SystemStack get() {
        if (instance == null) {
            instance = new SystemStack();
            instance.start();
        }
        return instance;
    }

    private SystemStack() {
        this.root = repositoryRoot();
        try {
            this.emptyEnvFile = Files.createTempFile("system-tests", ".env");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void start() {
        llm.start();
        for (String key : List.of("POSTGRES_ADMIN_PASSWORD", "DOCUMENT_DB_PASSWORD", "EXTRACTION_DB_PASSWORD",
                "COMPLIANCE_DB_PASSWORD", "RPA_DB_PASSWORD", "RABBITMQ_PASSWORD", "PORTAL_PASSWORD",
                "API_EXPERT_PASSWORD", "API_APPROVER_PASSWORD", "API_ADMIN_PASSWORD", "API_EXPERT_APPROVER_PASSWORD")) {
            env.put(key, randomSecret());
        }
        env.put("RABBITMQ_USERNAME", RABBITMQ_USERNAME);
        env.put("PORTAL_USERNAME", "rpa-bot");
        env.put("OLLAMA_CHAT_MODEL", "qwen2.5:7b-instruct");
        env.put("OLLAMA_EMBEDDING_MODEL", "bge-m3");
        env.put("PORTAL_FAIL_INVOICE_NO_PATTERN", "^" + FAILING_PREFIX);
        env.put("PORTAL_SLOW_INVOICE_NO_PATTERN", "^" + SLOW_PREFIX);
        env.put("FAKE_OLLAMA_PORT", String.valueOf(llm.port()));

        compose(Duration.ofMinutes(2), "down", "-v", "--remove-orphans");
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
        compose(Duration.ofMinutes(20), "up", "-d", "--build", "--wait");

        documents = new DocumentApi("http://localhost:" + port("document-service", 8080), "expert",
                env.get("API_EXPERT_PASSWORD"));
        contracts = new ContractApi("http://localhost:" + port("compliance-service", 8080), "expert",
                env.get("API_EXPERT_PASSWORD"));
        seedCompliantContracts();
        postgresPort = port("postgres", 5432);
        rabbitConnections = new CachingConnectionFactory("localhost", port("rabbitmq", 5672));
        rabbitConnections.setUsername(RABBITMQ_USERNAME);
        rabbitConnections.setPassword(env.get("RABBITMQ_PASSWORD"));
        rabbitConnections.setVirtualHost("invoice");
    }

    private void stop() {
        try {
            Path log = root.resolve("system-tests/target/compose.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, compose(Duration.ofMinutes(1), "logs", "--no-color", "--timestamps"));
        } catch (RuntimeException | IOException e) {
            System.err.println("compose logları yazılamadı: " + e.getMessage());
        }
        if (rabbitConnections != null) {
            rabbitConnections.destroy();
        }
        if (!Boolean.getBoolean("system-tests.keep")) {
            compose(Duration.ofMinutes(3), "down", "-v", "--remove-orphans");
        }
        llm.stop();
    }

    FakeLlm llm() {
        return llm;
    }

    ContractApi contracts() {
        return contracts;
    }

    /**
     * B-46: uyum kontrolü gerçektir; sözleşmesiz fatura {@code NO_CONTRACT} → {@code PENDING_APPROVAL} olur. Testlerin
     * B-16 tedarikçilerine (testler fatura numarasını değiştirir, VKN'yi değil) faturayla uyumlu birer sözleşme yüklenir;
     * temiz faturalar eskisi gibi {@code POSTED} olur, uyum yolu (embedding, LLM, grounding) her faturada çalışır.
     */
    private void seedCompliantContracts() {
        SyntheticContracts.compliantSet().forEach(contracts::uploadAndAwaitReady);
    }

    DocumentApi documents() {
        return documents;
    }

    /** Servis konteynerini SIGKILL ile öldürür: gerçek çökme, kapanış kancası çalışmaz. */
    void kill(String service) {
        compose(Duration.ofMinutes(1), "kill", service);
    }

    /**
     * Öldürülmüş servisi yeniden başlatır ve sağlıklı olmasını bekler. Docker yayımlanan rastgele host portunu yeniden
     * başlatmada değiştirir; dışa açık servisin adresi yeniden okunur.
     */
    void restart(String service) {
        compose(Duration.ofMinutes(5), "up", "-d", "--no-recreate", "--wait", service);
        if (service.equals("document-service")) {
            documents.rebase("http://localhost:" + port("document-service", 8080));
        }
    }

    /** Servisin compose logları (yalnız testin okuması için). */
    String logs(String service) {
        return compose(Duration.ofSeconds(30), "logs", "--no-color", "--no-log-prefix", service);
    }

    /** Veritabanına Postgres yöneticisiyle bağlanır (yalnızca testin okuması ve hazırlığı için). */
    JdbcClient database(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:%d/%s".formatted(postgresPort, name), "postgres",
                env.get("POSTGRES_ADMIN_PASSWORD"));
        return JdbcClient.create(dataSource);
    }

    /** Uygulama kullanıcısıyla {@code invoice} vhost'una yayın (aynı mesajı yeniden yayınlamak, zehirli mesaj). */
    RabbitTemplate rabbit() {
        return new RabbitTemplate(rabbitConnections);
    }

    private int port(String service, int containerPort) {
        String out = compose(Duration.ofSeconds(30), "port", service, String.valueOf(containerPort)).strip();
        String first = out.lines().findFirst().orElseThrow(() -> new IllegalStateException("Port yok: " + service));
        return Integer.parseInt(first.substring(first.lastIndexOf(':') + 1).strip());
    }

    private String compose(Duration timeout, String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-p", PROJECT,
                "--env-file", emptyEnvFile.toString(),
                "-f", root.resolve("docker-compose.yml").toString(),
                "-f", root.resolve("system-tests/compose.system-tests.yml").toString(),
                "--profile", "all"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        try {
            Path output = Files.createTempFile("compose", ".out");
            builder.redirectOutput(output.toFile());
            Process process = builder.start();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker compose " + String.join(" ", args) + " zaman aşımı");
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            Files.deleteIfExists(output);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("docker compose " + String.join(" ", args) + " başarısız ("
                        + process.exitValue() + "):\n" + tail(text));
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String tail(String text) {
        List<String> lines = text.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("docker-compose.yml"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("docker-compose.yml bulunamadı");
        }
        return dir;
    }

    private static String randomSecret() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder secret = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            secret.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return secret.toString();
    }
}
