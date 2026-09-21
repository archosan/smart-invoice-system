package com.archosan.invoice.document.api;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.document.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Ayarlar API'si (B-39, FR-A2): değerler string taşınır, değişiklik izi bırakır ve önbelleği beklemeden yansır. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"invoice.messaging.outbox.relay.enabled=false",
        "invoice.document.settings-cache-ttl=1h"})
class SettingsAdminIntegrationTest extends DocumentIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Settings settings;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void restoreDefaults() {
        jdbc.sql("DELETE FROM settings_changes").update();
        put("""
                {"confidenceThreshold": "0.80", "approvalAmountThreshold": "100000.00"}""");
        jdbc.sql("DELETE FROM settings_changes").update();
    }

    @Test
    void getReturnsBothThresholdsAsStrings() {
        JsonNode body = json.readTree(call(HttpMethod.GET, null).getBody());

        assertThat(body.path("confidenceThreshold").isString()).isTrue();
        assertThat(body.path("confidenceThreshold").asString()).isEqualTo("0.80");
        assertThat(body.path("approvalAmountThreshold").asString()).isEqualTo("100000.00");
    }

    @Test
    void partialUpdateChangesOnlyGivenValueTakesEffectAtOnceAndLeavesTrace() {
        assertThat(settings.approvalAmountThreshold()).isEqualByComparingTo("100000.00");   // önbelleğe al

        ResponseEntity<String> response = put("""
                {"approvalAmountThreshold": "250000.50"}""");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("approvalAmountThreshold").asString()).isEqualTo("250000.50");
        assertThat(body.path("confidenceThreshold").asString()).isEqualTo("0.80");
        // TTL 1 saat: önbellek boşaltılmasaydı eski değer görünürdü.
        assertThat(settings.approvalAmountThreshold()).isEqualByComparingTo("250000.50");
        assertThat(jdbc.sql("""
                        SELECT key || ':' || old_value || '>' || new_value || ':' || actor FROM settings_changes
                        """).query(String.class).list())
                .containsExactly("approval_amount_threshold:100000.00>250000.50:admin");
    }

    @Test
    void numericallySameValueLeavesNoTrace() {
        assertThat(put("""
                {"confidenceThreshold": "0.8"}""").getStatusCode().value()).isEqualTo(200);

        assertThat(jdbc.sql("SELECT count(*) FROM settings_changes").query(Long.class).single()).isZero();
    }

    @Test
    void invalidValuesAreRejectedAndNothingIsWritten() {
        for (String body : new String[] {
                "{}",
                """
                {"confidenceThreshold": "1.01"}""",
                """
                {"confidenceThreshold": "-0.1"}""",
                """
                {"confidenceThreshold": "0.12345"}""",
                """
                {"approvalAmountThreshold": "-1"}""",
                """
                {"approvalAmountThreshold": "10.001"}""",
                """
                {"approvalAmountThreshold": "yüz bin"}""",
                // Biri geçerli olsa da hiçbiri yazılmaz.
                """
                {"confidenceThreshold": "0.90", "approvalAmountThreshold": "-1"}"""}) {
            assertThat(put(body).getStatusCode().value()).as(body).isEqualTo(400);
        }
        assertThat(settings.confidenceThreshold()).isEqualByComparingTo("0.80");
        assertThat(jdbc.sql("SELECT count(*) FROM settings_changes").query(Long.class).single()).isZero();
    }

    private ResponseEntity<String> put(String body) {
        return call(HttpMethod.PUT, body);
    }

    private ResponseEntity<String> call(HttpMethod method, String body) {
        RestClient.RequestBodySpec request = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api/v1/admin/settings")
                .defaultHeaders(basicAuth(ADMIN))
                .defaultStatusHandler(status -> true, (req, response) -> { })
                .build()
                .method(method);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(String.class);
    }
}
