package com.archosan.invoice.compliance.api;

import com.archosan.invoice.compliance.ComplianceIntegrationTest;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.message.IngestContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sözleşme yükleme API'si (B-45, FR-C1, US-11): kayıt ve iç komut tek transaction'da, aynı dosya ikinci kez kayıt
 * açmaz, çakışan aralık uyarı döner, yetki ADR-22. Relay kapalı: komut outbox'ta doğrulanır, indeksleme çalışmaz.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "invoice.messaging.outbox.relay.enabled=false")
class ContractApiIntegrationTest extends ComplianceIntegrationTest {

    private static final Path CONTRACTS = Path.of("src/test/resources/contracts");

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private InvoiceMessageConverter converter;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void uploadStoresFileAndQueuesIngestionInOneStep() throws IOException {
        ResponseEntity<String> response = upload(EXPERT, pdf("contract-01.pdf"), "4810293756", "2026-01-01",
                "2026-12-31");

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        JsonNode body = json.readTree(response.getBody());
        UUID id = UUID.fromString(body.path("contractId").asString());
        assertThat(body.path("status").asString()).isEqualTo("INGESTING");
        assertThat(body.path("warnings")).isEmpty();
        assertThat(response.getHeaders().getLocation()).hasToString("http://localhost:" + port + "/api/v1/contracts/" + id);
        assertThat(jdbc.sql("SELECT supplier_vkn || '|' || valid_from || '|' || valid_to || '|' || uploaded_by "
                + "FROM contracts WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("4810293756|2026-01-01|2026-12-31|expert");
        IngestContract command = converter.readJson(jdbc.sql("""
                        SELECT payload::text FROM outbox WHERE aggregate_id = :id AND message_type = 'IngestContract'
                        """).param("id", id).query(String.class).single(), IngestContract.class);
        assertThat(command.contractId()).isEqualTo(id);
        assertThat(Files.readAllBytes(Path.of(URI.create(command.storageUri()))))
                .isEqualTo(Files.readAllBytes(CONTRACTS.resolve("contract-01.pdf")));
        assertThat(Files.list(STORAGE_DIR.resolve(".staging"))).isEmpty();

        JsonNode detail = json.readTree(get(EXPERT, "/" + id).getBody());
        assertThat(detail.path("contract").path("status").asString()).isEqualTo("INGESTING");
        assertThat(detail.path("chunks")).isEmpty();
    }

    @Test
    void sameFileTwiceReturnsExistingContract() throws IOException {
        byte[] pdf = pdf("contract-01.pdf");
        UUID first = id(upload(EXPERT, pdf, "4810293756", "2026-01-01", "2026-12-31"));

        ResponseEntity<String> second = upload(EXPERT, pdf, "4810293756", "2026-01-01", "2026-12-31");

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(second.getBody()).path("duplicate").asBoolean()).isTrue();
        assertThat(id(second)).isEqualTo(first);
        assertThat(count("contracts")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    /** DLQ'ya düşmüş (FAILED) sözleşme aynı dosyayla yeniden indekslenir. */
    @Test
    void failedContractIsReingestedWhenUploadedAgain() throws IOException {
        byte[] pdf = pdf("contract-01.pdf");
        UUID id = id(upload(EXPERT, pdf, "4810293756", "2026-01-01", "2026-12-31"));
        jdbc.sql("UPDATE contracts SET status = 'FAILED', failure_reason = 'DLQ: delivery_limit' WHERE id = :id")
                .param("id", id).update();

        ResponseEntity<String> again = upload(EXPERT, pdf, "4810293756", "2026-01-01", "2026-12-31");

        assertThat(again.getStatusCode().value()).isEqualTo(202);
        assertThat(id(again)).isEqualTo(id);
        assertThat(jdbc.sql("SELECT status || '|' || coalesce(failure_reason, '-') FROM contracts WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("INGESTING|-");
        assertThat(count("outbox")).isEqualTo(2);
    }

    @Test
    void overlappingRangeIsAcceptedWithWarning() throws IOException {
        UUID first = id(upload(EXPERT, pdf("contract-04.pdf"), "5172039486", "2026-01-01", "2026-12-31"));

        ResponseEntity<String> second = upload(EXPERT, pdf("contract-05.pdf"), "5172039486", "2026-07-01",
                "2027-06-30");

        assertThat(second.getStatusCode().value()).isEqualTo(202);
        assertThat(json.readTree(second.getBody()).path("warnings")).singleElement()
                .satisfies(w -> assertThat(w.asString()).contains(first.toString(), "CONTRACT_CONFLICT"));
        // Başka tedarikçi ya da kesişmeyen aralık uyarı vermez.
        assertThat(json.readTree(upload(EXPERT, pdf("contract-06.pdf"), "5172039486", "2025-01-01", "2025-12-31")
                .getBody()).path("warnings")).isEmpty();
        assertThat(json.readTree(get(EXPERT, "?vkn=5172039486").getBody())).hasSize(3);
    }

    @Test
    void invalidParametersAreRejectedWithoutWritingAnything() throws IOException {
        byte[] pdf = pdf("contract-01.pdf");

        assertThat(upload(EXPERT, pdf, "123", "2026-01-01", "2026-12-31").getStatusCode().value()).isEqualTo(400);
        assertThat(upload(EXPERT, pdf, "4810293756", "2026-12-31", "2026-01-01").getStatusCode().value())
                .isEqualTo(400);
        assertThat(upload(EXPERT, pdf, "4810293756", "2026-01-01", null).getStatusCode().value()).isEqualTo(400);
        assertThat(upload(EXPERT, pdf, "4810293756", "31.12.2026", "2026-12-31").getStatusCode().value())
                .isEqualTo(400);
        assertThat(upload(EXPERT, "metin".getBytes(StandardCharsets.UTF_8), "4810293756", "2026-01-01", "2026-12-31")
                .getStatusCode().value()).isEqualTo(415);
        assertThat(count("contracts")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(get(EXPERT, "/" + UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
    }

    /** ADR-22: yükleme uzman, okuma giriş yapmış herkes. */
    @Test
    void onlyExpertUploadsAndEveryoneLoggedInReads() throws IOException {
        byte[] pdf = pdf("contract-01.pdf");

        assertThat(upload(null, pdf, "4810293756", "2026-01-01", "2026-12-31").getStatusCode().value())
                .isEqualTo(401);
        assertThat(upload(APPROVER, pdf, "4810293756", "2026-01-01", "2026-12-31").getStatusCode().value())
                .isEqualTo(403);
        assertThat(get(null, "").getStatusCode().value()).isEqualTo(401);
        assertThat(get(APPROVER, "").getStatusCode().value()).isEqualTo(200);
        assertThat(count("contracts")).isZero();
    }

    private static byte[] pdf(String file) throws IOException {
        return Files.readAllBytes(CONTRACTS.resolve(file));
    }

    private UUID id(ResponseEntity<String> response) {
        return UUID.fromString(json.readTree(response.getBody()).path("contractId").asString());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private ResponseEntity<String> upload(String user, byte[] content, String vkn, String from, String to) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "sozlesme.pdf";
            }
        });
        parts.add("supplierVkn", vkn);
        parts.add("validFrom", from);
        if (to != null) {
            parts.add("validTo", to);
        }
        return client(user).post().uri("/api/v1/contracts").contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> get(String user, String path) {
        return client(user).get().uri("/api/v1/contracts" + path).retrieve().toEntity(String.class);
    }

    private RestClient client(String user) {
        Consumer<HttpHeaders> auth = user == null ? headers -> { } : basicAuth(user);
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(auth)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }
}
