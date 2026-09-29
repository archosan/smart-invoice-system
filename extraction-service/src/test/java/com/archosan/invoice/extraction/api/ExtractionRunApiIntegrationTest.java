package com.archosan.invoice.extraction.api;

import com.archosan.invoice.extraction.ExtractionIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Ham LLM çıktısı inceleme API'si (B-47, FR-E6): denemeler sırayla, yetki ADMIN ve EXPERT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "invoice.messaging.outbox.relay.enabled=false")
class ExtractionRunApiIntegrationTest extends ExtractionIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void listsAttemptsInOrderWithRawOutputAndPurgeMarker() {
        UUID documentId = UUID.randomUUID();
        insert(documentId, 2, null, "LLM yanıtı çözülemedi", "now() - interval '1 day'");
        insert(documentId, 1, "Elbette, işte JSON: {bozuk", "LLM yanıtı çözülemedi", "now() - interval '1 day'");
        insert(documentId, 3, "{\"invoiceNo\":\"FTR-1\"}", null, "now()");
        jdbc.sql("UPDATE extraction_runs SET raw_output_purged_at = now() WHERE attempt_no = 2").update();
        insert(UUID.randomUUID(), 1, "başka belge", null, "now()");

        ResponseEntity<String> response = get("admin", "?documentId=" + documentId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode runs = json.readTree(response.getBody());
        assertThat(runs).extracting(r -> r.path("attemptNo").asInt()).containsExactly(1, 2, 3);
        assertThat(runs.get(0).path("rawOutput").asString()).isEqualTo("Elbette, işte JSON: {bozuk");
        assertThat(runs.get(0).path("model").asString()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(runs.get(0).path("promptVersion").asString()).isEqualTo("v3");
        assertThat(runs.get(0).path("parseError").asString()).isEqualTo("LLM yanıtı çözülemedi");
        assertThat(runs.get(1).path("rawOutput").isNull()).isTrue();
        assertThat(runs.get(1).path("rawOutputPurgedAt").isNull()).isFalse();
        assertThat(json.readTree(get("expert", "?documentId=" + UUID.randomUUID()).getBody())).isEmpty();
    }

    @Test
    void onlyAdminAndExpertReadRawOutput() {
        String query = "?documentId=" + UUID.randomUUID();

        assertThat(get(null, query).getStatusCode().value()).isEqualTo(401);
        assertThat(get("approver", query).getStatusCode().value()).isEqualTo(403);
        assertThat(get("expert", query).getStatusCode().value()).isEqualTo(200);
        assertThat(get("admin", "").getStatusCode().value()).as("documentId zorunlu").isEqualTo(400);
        assertThat(get("admin", "?documentId=abc").getStatusCode().value()).isEqualTo(400);
    }

    private void insert(UUID documentId, int attempt, String raw, String error, String createdAt) {
        jdbc.sql("""
                        INSERT INTO extraction_runs (document_id, attempt_no, model, prompt_version, raw_output,
                                                     parse_error, duration_ms, created_at)
                        VALUES (:id, :attempt, 'qwen2.5:7b-instruct', 'v3', :raw, :error, 1200, %s)
                        """.formatted(createdAt))
                .param("id", documentId).param("attempt", attempt).param("raw", raw).param("error", error).update();
    }

    private ResponseEntity<String> get(String user, String query) {
        Consumer<HttpHeaders> auth = user == null ? headers -> { } : basicAuth(user);
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(auth)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build()
                .get().uri("/api/v1/extraction-runs" + query)
                .retrieve().toEntity(String.class);
    }
}
