package com.archosan.invoice.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Testlerin paylaştığı konteynerler. Her biri JVM başına bir kez, ilk kullanımda başlar ve JVM kapanınca
 * Testcontainers tarafından kaldırılır; farklı Spring context'leri aynı konteynerleri kullanır.
 *
 * <p>Konteynerler compose ile aynı imaj ve {@code infra/} dosyalarıyla açılır:
 * <ul>
 *   <li>RabbitMQ: {@code rabbitmq.conf}, {@code definitions.json} ve {@code init-user.sh}; guest yoktur, uygulama
 *       kullanıcısının {@code configure} izni yoktur (ADR-17). Testler topolojiyi kuramaz, gerçeğini kullanır.</li>
 *   <li>Postgres: {@code 01-databases.sql}; dört veritabanı, dört kullanıcı, pgvector (ADR-06).</li>
 *   <li>Redis: compose'daki imaj.</li>
 * </ul>
 * Şifreler her çalıştırmada rastgele üretilir; koda ve teste secret yazılmaz (NFR-10).
 *
 * <p>Konteynerler testler arasında paylaşıldığı için veri temizliği her testin kendi işidir.
 */
public final class InfrastructureContainers {

    public static final String RABBITMQ_VHOST = "invoice";
    public static final String RABBITMQ_USERNAME = "invoice";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private InfrastructureContainers() {
    }

    /** Uygulama kullanıcısıyla {@code invoice} vhost'una bağlantı ayarlarını verir. */
    public static void registerRabbitMq(DynamicPropertyRegistry registry) {
        GenericContainer<?> rabbit = RabbitMq.CONTAINER;
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> RABBITMQ_USERNAME);
        registry.add("spring.rabbitmq.password", () -> RabbitMq.PASSWORD);
        registry.add("spring.rabbitmq.virtual-host", () -> RABBITMQ_VHOST);
    }

    /**
     * RabbitMQ konteynerinde {@code rabbitmqctl -q --no-table-headers <args>} çalıştırır ve çıktıyı döner; broker'ın
     * AMQP'den görünmeyen durumunu okumak için (ör. single active consumer'da hangi consumer'ın beklemede olduğu, B-27).
     */
    public static String rabbitmqctl(String... args) {
        String[] command = new String[args.length + 3];
        command[0] = "rabbitmqctl";
        command[1] = "-q";
        command[2] = "--no-table-headers";
        System.arraycopy(args, 0, command, 3, args.length);
        try {
            var result = RabbitMq.CONTAINER.execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("rabbitmqctl başarısız (" + result.getExitCode() + "): "
                        + result.getStderr());
            }
            return result.getStdout();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Servisin kendi kullanıcısıyla kendi veritabanına bağlantı ayarlarını verir. */
    public static void registerPostgres(DynamicPropertyRegistry registry, ServiceDatabase database) {
        PostgreSQLContainer postgres = Postgres.CONTAINER;
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(5432), database.databaseName()));
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", () -> Postgres.PASSWORDS.get(database));
    }

    /**
     * Konteynerin superuser'ıyla servis veritabanına bağlantı; yalnızca test temizliği için (B-42). Servis kodu her
     * zaman kendi kullanıcısıyla çalışır; yalnız eklenen tabloları ({@code status_transitions}) bu kullanıcı
     * silemez, temizlik superuser ve {@code session_replication_role = replica} ile tetikleyicileri atlar.
     */
    public static Connection superuserConnection(ServiceDatabase database) throws SQLException {
        PostgreSQLContainer postgres = Postgres.CONTAINER;
        return DriverManager.getConnection("jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(),
                postgres.getMappedPort(5432), database.databaseName()), postgres.getUsername(), postgres.getPassword());
    }

    public static void registerRedis(DynamicPropertyRegistry registry) {
        GenericContainer<?> redis = Redis.CONTAINER;
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // Holder sınıfları: her konteyner yalnızca ilk erişimde başlar (Redis'i kullanmayan test onu açmaz).

    private static final class RabbitMq {

        static final String PASSWORD = randomSecret();
        static final GenericContainer<?> CONTAINER = start();

        @SuppressWarnings("resource")
        private static GenericContainer<?> start() {
            Path infra = infraDirectory().resolve("rabbitmq");
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("rabbitmq:4-management"))
                    .withCopyFileToContainer(MountableFile.forHostPath(infra.resolve("rabbitmq.conf")),
                            "/etc/rabbitmq/rabbitmq.conf")
                    .withCopyFileToContainer(MountableFile.forHostPath(infra.resolve("definitions.json")),
                            "/etc/rabbitmq/definitions.json")
                    .withCopyFileToContainer(MountableFile.forHostPath(infra.resolve("init-user.sh"), 0755),
                            "/opt/invoice/init-user.sh")
                    .withEnv("RABBITMQ_USERNAME", RABBITMQ_USERNAME)
                    .withEnv("RABBITMQ_PASSWORD", PASSWORD)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/opt/invoice/init-user.sh"))
                    .withCommand("rabbitmq-server")
                    .withExposedPorts(5672)
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
            container.start();
            return container;
        }
    }

    private static final class Postgres {

        static final Map<ServiceDatabase, String> PASSWORDS = passwords();
        static final PostgreSQLContainer CONTAINER = start();

        @SuppressWarnings("resource")
        private static PostgreSQLContainer start() {
            Path init = infraDirectory().resolve("postgres/init/01-databases.sql");
            PostgreSQLContainer container = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                    .withPassword(randomSecret())
                    .withCopyFileToContainer(MountableFile.forHostPath(init),
                            "/docker-entrypoint-initdb.d/01-databases.sql");
            PASSWORDS.forEach((database, password) -> container.withEnv(database.passwordVariable(), password));
            container.start();
            return container;
        }

        private static Map<ServiceDatabase, String> passwords() {
            Map<ServiceDatabase, String> passwords = new EnumMap<>(ServiceDatabase.class);
            for (ServiceDatabase database : ServiceDatabase.values()) {
                passwords.put(database, randomSecret());
            }
            return Map.copyOf(passwords);
        }
    }

    private static final class Redis {

        static final GenericContainer<?> CONTAINER = start();

        @SuppressWarnings("resource")
        private static GenericContainer<?> start() {
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
            container.start();
            return container;
        }
    }

    /** Modül dizininden yukarı çıkarak repo kökündeki {@code infra/} dizinini bulur. */
    static Path infraDirectory() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path infra = dir.resolve("infra");
            if (Files.isRegularFile(infra.resolve("rabbitmq/definitions.json"))) {
                return infra;
            }
        }
        throw new IllegalStateException("infra/ dizini bulunamadı; testler repo içinden çalıştırılmalı");
    }

    private static String randomSecret() {
        StringBuilder secret = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            secret.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return secret.toString();
    }
}
