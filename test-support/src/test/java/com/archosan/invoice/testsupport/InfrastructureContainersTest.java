package com.archosan.invoice.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Konteynerlerin compose ile aynı kurulumla açıldığını doğrular (RabbitMQ common-messaging testlerinde). */
class InfrastructureContainersTest {

    @ParameterizedTest
    @EnumSource(ServiceDatabase.class)
    void eachServiceUserConnectsToItsOwnDatabase(ServiceDatabase database) throws SQLException {
        Map<String, Supplier<Object>> props = postgresProperties(database);

        try (Connection connection = connect(props, props.get("spring.datasource.url").get().toString())) {
            assertThat(connection.getCatalog()).isEqualTo(database.databaseName());
        }
    }

    @Test
    void serviceUserCannotConnectToAnotherServicesDatabase() {
        Map<String, Supplier<Object>> document = postgresProperties(ServiceDatabase.DOCUMENT);
        String rpaUrl = postgresProperties(ServiceDatabase.RPA).get("spring.datasource.url").get().toString();

        assertThatThrownBy(() -> connect(document, rpaUrl).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
    }

    @Test
    void complianceDatabaseHasPgvector() throws SQLException {
        Map<String, Supplier<Object>> props = postgresProperties(ServiceDatabase.COMPLIANCE);

        try (Connection connection = connect(props, props.get("spring.datasource.url").get().toString());
                var rs = connection.createStatement()
                        .executeQuery("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void redisAnswersPing() throws Exception {
        Map<String, Supplier<Object>> props = new HashMap<>();
        InfrastructureContainers.registerRedis(props::put);

        try (Socket socket = new Socket(props.get("spring.data.redis.host").get().toString(),
                (Integer) props.get("spring.data.redis.port").get())) {
            OutputStream out = socket.getOutputStream();
            out.write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(in.readLine()).isEqualTo("+PONG");
        }
    }

    @Test
    void findsInfraDirectoryFromModule() {
        assertThat(InfrastructureContainers.infraDirectory().resolve("rabbitmq/definitions.json")).exists();
    }

    private static Map<String, Supplier<Object>> postgresProperties(ServiceDatabase database) {
        Map<String, Supplier<Object>> props = new HashMap<>();
        InfrastructureContainers.registerPostgres(props::put, database);
        return props;
    }

    private static Connection connect(Map<String, Supplier<Object>> props, String url) throws SQLException {
        return DriverManager.getConnection(url, props.get("spring.datasource.username").get().toString(),
                props.get("spring.datasource.password").get().toString());
    }
}
