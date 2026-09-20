package com.archosan.invoice.document.api;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.document.persistence.InvoiceDataRepository;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.PostToPortal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yönetici DLQ API'si (B-38, FR-A1, US-10, ADR-18): yeniden işletme durum geçişiyledir, mesaj taşınmaz. Relay kapalı:
 * yazılan {@code PostToPortal} outbox'ta doğrulanır, ortak broker'a gitmez.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "invoice.messaging.outbox.relay.enabled=false")
class DeadLetterAdminIntegrationTest extends DocumentIntegrationTest {

    private static final InvoiceFields FIELDS = new InvoiceFields("ACME A.Ş.", "1234567890", "FTR-1",
            LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 30),
            List.of(new InvoiceLine("Rulman", new BigDecimal("1"), new BigDecimal("100.00"), 20)),
            new BigDecimal("100.00"), new BigDecimal("20.00"), new BigDecimal("120.00"), "TRY");

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private InvoiceDataRepository invoiceData;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void reprocessMovesRecordBackToQueueWithNewCommandAndClosesDeadLetter() {
        UUID documentId = document("RPA_FAILED");
        UUID deadLetterId = deadLetter(documentId, "PostToPortal");

        ResponseEntity<String> response = call(HttpMethod.POST, "/" + deadLetterId + "/reprocess");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(response.getBody());
        UUID command = UUID.fromString(body.path("commandMessageId").asString());
        assertThat(status(documentId)).isEqualTo("QUEUED_FOR_RPA");
        assertThat(jdbc.sql("SELECT status FROM dead_letters WHERE id = :id").param("id", deadLetterId)
                .query(String.class).single()).isEqualTo("REPROCESSED");
        // Yeni komut outbox'ta ve bekleyen komut o (A4).
        PostToPortal sent = converter.readJson(jdbc.sql("""
                        SELECT payload::text FROM outbox WHERE id = :id AND message_type = 'PostToPortal'
                        """).param("id", command).query(String.class).single(), PostToPortal.class);
        assertThat(sent.invoiceNo()).isEqualTo("FTR-1");
        assertThat(sent.grandTotal()).isEqualByComparingTo("120.00");
        assertThat(jdbc.sql("SELECT pending_rpa_command_id FROM documents WHERE id = :id").param("id", documentId)
                .query(UUID.class).single()).isEqualTo(command);
        assertThat(jdbc.sql("""
                        SELECT from_status || '>' || to_status || ':' || trigger_event || ':' || actor
                        FROM status_transitions WHERE document_id = :id
                        """).param("id", documentId).query(String.class).list())
                .containsExactly("RPA_FAILED>QUEUED_FOR_RPA:ADMIN_REPROCESS:admin");
    }

    @Test
    void reprocessIsRefusedWhenStateDoesNotAllowIt() {
        UUID notFailed = document("POSTED");
        UUID onPosted = deadLetter(notFailed, "PostToPortal");
        UUID failed = document("RPA_FAILED");
        UUID extraction = deadLetter(failed, "ExtractInvoice");

        assertThat(call(HttpMethod.POST, "/" + onPosted + "/reprocess").getStatusCode().value()).isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/" + extraction + "/reprocess").getStatusCode().value()).isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/" + UUID.randomUUID() + "/reprocess").getStatusCode().value())
                .isEqualTo(404);
        // Hiçbiri bir şey değiştirmedi.
        assertThat(status(notFailed)).isEqualTo("POSTED");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM dead_letters WHERE status = 'OPEN'").query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void reprocessedDeadLetterCannotBeReprocessedTwice() {
        UUID deadLetterId = deadLetter(document("RPA_FAILED"), "PostToPortal");

        assertThat(call(HttpMethod.POST, "/" + deadLetterId + "/reprocess").getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/" + deadLetterId + "/reprocess").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void ignoreClosesOpenDeadLetterOnce() {
        UUID deadLetterId = deadLetter(document("RPA_FAILED"), "PostToPortal");

        assertThat(call(HttpMethod.POST, "/" + deadLetterId + "/ignore").getStatusCode().value()).isEqualTo(204);
        assertThat(call(HttpMethod.POST, "/" + deadLetterId + "/ignore").getStatusCode().value()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT status FROM dead_letters WHERE id = :id").param("id", deadLetterId)
                .query(String.class).single()).isEqualTo("IGNORED");
    }

    @Test
    void listFiltersByStatusAndDetailEmbedsBodyAsJson() {
        UUID documentId = document("RPA_FAILED");
        UUID open = deadLetter(documentId, "PostToPortal");
        UUID ignored = deadLetter(documentId, "PostToPortal");
        call(HttpMethod.POST, "/" + ignored + "/ignore");

        JsonNode list = json.readTree(call(HttpMethod.GET, "?status=OPEN").getBody());
        assertThat(list.path("totalElements").asLong()).isEqualTo(1);
        assertThat(list.path("content").get(0).path("id").asString()).isEqualTo(open.toString());
        assertThat(call(HttpMethod.GET, "?status=HEPSI").getStatusCode().value()).isEqualTo(400);

        JsonNode detail = json.readTree(call(HttpMethod.GET, "/" + open).getBody());
        assertThat(detail.path("body").path("invoiceNo").asString()).isEqualTo("FTR-1");
        assertThat(detail.path("messageType").asString()).isEqualTo("PostToPortal");
    }

    private UUID document(String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status)
                        VALUES (:id, :sha, 'file:///data/documents/x.pdf', :status)
                        """)
                .param("id", id).param("sha", (id.toString() + id).replace("-", "").substring(0, 64))
                .param("status", status).update();
        invoiceData.save(id, FIELDS, List.of(), InvoiceDataRepository.Source.LLM);
        return id;
    }

    private UUID deadLetter(UUID documentId, String messageType) {
        UUID id = UUID.randomUUID();
        String body = converter.toJson(new PostToPortal(documentId, "1234567890", "ACME A.Ş.", "FTR-1",
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 30), new BigDecimal("120.00"),
                new BigDecimal("20.00"), "TRY"));
        jdbc.sql("""
                        INSERT INTO dead_letters
                            (id, kind, source_queue, message_type, message_id, document_id, body, status)
                        VALUES (:id, 'DLQ', 'rpa.post-to-portal.q', :type, :messageId, :documentId,
                                CAST(:body AS jsonb), 'OPEN')
                        """)
                .param("id", id).param("type", messageType).param("messageId", UUID.randomUUID())
                .param("documentId", documentId).param("body", body).update();
        return id;
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM documents WHERE id = :id").param("id", id).query(String.class).single();
    }

    private ResponseEntity<String> call(HttpMethod method, String path) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api/v1/admin/dead-letters")
                .defaultHeaders(basicAuth(ADMIN))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build()
                .method(method).uri(path)
                .retrieve()
                .toEntity(String.class);
    }
}
