package com.archosan.invoice.document.api;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.document.persistence.InvoiceDataRepository;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.PostToPortal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
 * İnsan adımları (B-40, FR-D7, US-13): düzeltme {@code If-Match} ile ve kurallardan geçerek, onay ve gerekçeli ret.
 * Relay kapalı: yazılan komutlar outbox'ta doğrulanır.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "invoice.messaging.outbox.relay.enabled=false")
class DocumentReviewIntegrationTest extends DocumentIntegrationTest {

    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 9, 30);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 10, 30);
    /** LLM'in yanlış okuduğu: miktar 15 sanılmış, kalem toplamı ara toplamı tutmuyor. */
    private static final InvoiceFields LLM = fields("15", "FTR-1");
    private static final InvoiceFields CORRECTED = fields("1.5", "FTR-1");

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
    void correctionWithCurrentVersionRevalidatesAndSendsComplianceCheck() {
        UUID id = document("NEEDS_REVIEW", 2);
        ResponseEntity<String> before = call(HttpMethod.GET, id, "", null, null);
        assertThat(before.getHeaders().getETag()).isEqualTo("\"2\"");

        ResponseEntity<String> response = call(HttpMethod.PUT, id, "/fields", before.getHeaders().getETag(),
                converter.writeJson(CORRECTED));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"4\"");
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("status").asString()).isEqualTo("VALIDATED");
        assertThat(body.path("fields").path("lines").get(0).path("quantity").asString()).isEqualTo("1.5");
        assertThat(body.path("ruleResults")).allSatisfy(rule -> assertThat(rule.path("passed").asBoolean()).isTrue());
        assertThat(jdbc.sql("SELECT source FROM invoice_data WHERE document_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("EXPERT_CORRECTION");
        assertThat(converter.readJson(outbox(id, "CheckCompliance"), CheckCompliance.class).lines())
                .isEqualTo(CORRECTED.lines());
        assertThat(transitions(id)).containsExactly(
                "NEEDS_REVIEW>EXTRACTED:EXPERT_CORRECTION:expert:",
                "EXTRACTED>VALIDATED:EXPERT_CORRECTION:document-service:uzman düzeltmesi, kurallar geçti");
    }

    @Test
    void correctionCopiesSearchFieldsButKeepsLlmConfidence() {
        UUID id = document("NEEDS_REVIEW", 0);

        call(HttpMethod.PUT, id, "/fields", "\"0\"", converter.writeJson(fields("1.5", "FTR-DUZELTILDI")));

        assertThat(jdbc.sql("SELECT invoice_no || '|' || confidence_score FROM documents WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("FTR-DUZELTILDI|0.4500");
    }

    @Test
    void correctionWithoutIfMatchOrWithStaleVersionChangesNothing() {
        UUID id = document("NEEDS_REVIEW", 3);
        String body = converter.writeJson(CORRECTED);

        assertThat(call(HttpMethod.PUT, id, "/fields", null, body).getStatusCode().value()).isEqualTo(428);
        assertThat(call(HttpMethod.PUT, id, "/fields", "\"2\"", body).getStatusCode().value()).isEqualTo(412);
        assertThat(call(HttpMethod.PUT, id, "/fields", "*", body).getStatusCode().value()).isEqualTo(400);
        assertUnchanged(id, "NEEDS_REVIEW");
    }

    @Test
    void correctionThatBreaksRulesIsRefusedWithViolations() {
        UUID id = document("NEEDS_REVIEW", 0);

        ResponseEntity<String> response = call(HttpMethod.PUT, id, "/fields", "\"0\"", converter.writeJson(LLM));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        JsonNode violations = json.readTree(response.getBody()).path("violations");
        assertThat(violations).extracting(v -> v.path("rule").asString())
                .containsExactlyInAnyOrder("LINES_SUM_EQUALS_SUBTOTAL", "VAT_MATCHES_LINES");
        assertUnchanged(id, "NEEDS_REVIEW");
    }

    @Test
    void correctionOutsideReviewIsConflictAndUnknownIsNotFound() {
        UUID id = document("PENDING_APPROVAL", 0);
        String body = converter.writeJson(CORRECTED);

        assertThat(call(HttpMethod.PUT, id, "/fields", "\"0\"", body).getStatusCode().value()).isEqualTo(409);
        assertThat(call(HttpMethod.PUT, UUID.randomUUID(), "/fields", "\"0\"", body).getStatusCode().value())
                .isEqualTo(404);
        assertUnchanged(id, "PENDING_APPROVAL");
    }

    @Test
    void approvalQueuesForPortalWithPendingCommand() {
        UUID id = document("PENDING_APPROVAL", 0);

        ResponseEntity<String> response = call(HttpMethod.POST, id, "/approve", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(response.getBody()).path("status").asString()).isEqualTo("QUEUED_FOR_RPA");
        PostToPortal command = converter.readJson(outbox(id, "PostToPortal"), PostToPortal.class);
        assertThat(command.invoiceNo()).isEqualTo("FTR-1");
        assertThat(jdbc.sql("SELECT pending_rpa_command_id FROM documents WHERE id = :id").param("id", id)
                .query(UUID.class).single()).isEqualTo(jdbc.sql("""
                        SELECT id FROM outbox WHERE aggregate_id = :id AND message_type = 'PostToPortal'
                        """).param("id", id).query(UUID.class).single());
        assertThat(transitions(id)).containsExactly(
                "PENDING_APPROVAL>COMPLIANCE_CHECKED:APPROVE:approver:",
                "COMPLIANCE_CHECKED>QUEUED_FOR_RPA:APPROVE:document-service:");
        assertThat(call(HttpMethod.POST, id, "/approve", null, null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void rejectionNeedsReasonAndKeepsItInHistory() {
        UUID review = document("NEEDS_REVIEW", 0);
        UUID approval = document("PENDING_APPROVAL", 0);

        assertThat(call(HttpMethod.POST, review, "/reject", null, "{\"reason\": \"  \"}").getStatusCode().value())
                .isEqualTo(400);
        assertThat(call(HttpMethod.POST, review, "/reject", null, "{}").getStatusCode().value()).isEqualTo(400);
        assertUnchanged(review, "NEEDS_REVIEW");

        assertThat(call(HttpMethod.POST, review, "/reject", null, "{\"reason\": \"Fatura bize ait değil\"}")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, approval, "/reject", null, "{\"reason\": \"Sipariş yok\"}")
                .getStatusCode().value()).isEqualTo(200);

        assertThat(transitions(review)).containsExactly("NEEDS_REVIEW>REJECTED:REJECT:expert:Fatura bize ait değil");
        assertThat(transitions(approval)).containsExactly("PENDING_APPROVAL>REJECTED:REJECT:approver:Sipariş yok");
        assertThat(call(HttpMethod.POST, review, "/reject", null, "{\"reason\": \"tekrar\"}").getStatusCode().value())
                .isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id IN (:ids)")
                .param("ids", List.of(review, approval)).query(Long.class).single()).isZero();
    }

    @Test
    void correctionToAnExistingInvoiceBecomesSuspectedDuplicate() {
        UUID posted = document("POSTED", 0);
        jdbc.sql("UPDATE documents SET supplier_vkn = '1234567890' WHERE id = :id").param("id", posted).update();
        UUID id = document("NEEDS_REVIEW", 0);

        ResponseEntity<String> response = call(HttpMethod.PUT, id, "/fields", "\"0\"", converter.writeJson(CORRECTED));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("status").asString()).isEqualTo("DUPLICATE_SUSPECTED");
        assertThat(body.path("duplicateOf")).extracting(JsonNode::asString).containsExactly(posted.toString());
        assertThat(transitions(id)).last().isEqualTo(
                "EXTRACTED>DUPLICATE_SUSPECTED:EXPERT_CORRECTION:document-service:mükerrer şüphesi: " + posted
                        + " (POSTED)");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id").param("id", id)
                .query(Long.class).single()).isZero();
    }

    @Test
    void notDuplicateDecisionValidatesAndSendsComplianceCheck() {
        UUID id = document("DUPLICATE_SUSPECTED", 0);

        ResponseEntity<String> response = call(HttpMethod.POST, id, "/duplicate-decision", null,
                "{\"duplicate\": false}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(response.getBody()).path("status").asString()).isEqualTo("VALIDATED");
        assertThat(converter.readJson(outbox(id, "CheckCompliance"), CheckCompliance.class).documentId())
                .isEqualTo(id);
        assertThat(transitions(id)).containsExactly("DUPLICATE_SUSPECTED>VALIDATED:NOT_DUPLICATE:expert:");
    }

    @Test
    void duplicateDecisionNeedsReasonAndRejects() {
        UUID id = document("DUPLICATE_SUSPECTED", 0);

        assertThat(call(HttpMethod.POST, id, "/duplicate-decision", null, "{\"duplicate\": true}")
                .getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, id, "/duplicate-decision", null, "{\"reason\": \"x\"}")
                .getStatusCode().value()).isEqualTo(400);
        assertUnchanged(id, "DUPLICATE_SUSPECTED");

        assertThat(call(HttpMethod.POST, id, "/duplicate-decision", null,
                "{\"duplicate\": true, \"reason\": \"Aynı fatura, ikinci tarama\"}").getStatusCode().value())
                .isEqualTo(200);
        assertThat(transitions(id)).containsExactly(
                "DUPLICATE_SUSPECTED>REJECTED:DUPLICATE_CONFIRMED:expert:Aynı fatura, ikinci tarama");
        assertThat(call(HttpMethod.POST, id, "/duplicate-decision", null, "{\"duplicate\": false}")
                .getStatusCode().value()).isEqualTo(409);
    }

    /** B-42, US-12: geçmiş eskiden yeniye, kim, neden, ne zaman. */
    @Test
    void historyListsTransitionsOldestFirstAndUnknownIsNotFound() {
        UUID id = document("PENDING_APPROVAL", 0);
        call(HttpMethod.POST, id, "/reject", null, "{\"reason\": \"Sipariş yok\"}");
        UUID quiet = document("RECEIVED", 0);

        ResponseEntity<String> response = call(HttpMethod.GET, id, "/history", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode entry = json.readTree(response.getBody()).get(0);
        assertThat(entry.path("from").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(entry.path("to").asString()).isEqualTo("REJECTED");
        assertThat(entry.path("event").asString()).isEqualTo("REJECT");
        assertThat(entry.path("actor").asString()).isEqualTo("approver");
        assertThat(entry.path("reason").asString()).isEqualTo("Sipariş yok");
        assertThat(entry.path("messageId").isNull()).isTrue();
        assertThat(entry.path("at").asString()).isNotBlank();
        assertThat(json.readTree(call(HttpMethod.GET, quiet, "/history", null, null).getBody())).isEmpty();
        assertThat(call(HttpMethod.GET, UUID.randomUUID(), "/history", null, null).getStatusCode().value())
                .isEqualTo(404);
    }

    /** B-43: onaycı kendi yüklediğini onaylayamaz, ama başkasınınkini onaylar ve kendisininkini reddedebilir. */
    @Test
    void approverCannotApproveOwnUpload() {
        UUID own = document("PENDING_APPROVAL", 0);
        UUID others = document("PENDING_APPROVAL", 0);
        UUID ownToReject = document("PENDING_APPROVAL", 0);
        jdbc.sql("UPDATE documents SET uploaded_by = :user WHERE id IN (:ids)").param("user", EXPERT_APPROVER)
                .param("ids", List.of(own, ownToReject)).update();
        jdbc.sql("UPDATE documents SET uploaded_by = :user WHERE id = :id").param("user", EXPERT)
                .param("id", others).update();

        ResponseEntity<String> refused = callAs(EXPERT_APPROVER, HttpMethod.POST, own, "/approve", null, null);

        assertThat(refused.getStatusCode().value()).isEqualTo(403);
        assertThat(json.readTree(refused.getBody()).path("detail").asString()).contains("kendi yüklediği");
        assertUnchanged(own, "PENDING_APPROVAL");
        assertThat(callAs(EXPERT_APPROVER, HttpMethod.POST, others, "/approve", null, null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(transitions(others)).first().isEqualTo(
                "PENDING_APPROVAL>COMPLIANCE_CHECKED:APPROVE:expert-approver:");
        assertThat(callAs(EXPERT_APPROVER, HttpMethod.POST, ownToReject, "/reject", null,
                "{\"reason\": \"Yanlış yüklendi\"}").getStatusCode().value()).isEqualTo(200);
    }

    /** B-43: ret, kaydın durumundaki rolü ister. */
    @Test
    void rejectionNeedsTheRoleOfTheState() {
        UUID approval = document("PENDING_APPROVAL", 0);
        UUID review = document("NEEDS_REVIEW", 0);
        String body = "{\"reason\": \"x\"}";

        assertThat(callAs(EXPERT, HttpMethod.POST, approval, "/reject", null, body).getStatusCode().value())
                .isEqualTo(403);
        assertThat(callAs(APPROVER, HttpMethod.POST, review, "/reject", null, body).getStatusCode().value())
                .isEqualTo(403);
        assertUnchanged(approval, "PENDING_APPROVAL");
        assertUnchanged(review, "NEEDS_REVIEW");
    }

    private static InvoiceFields fields(String quantity, String invoiceNo) {
        return new InvoiceFields("ACME A.Ş.", "1234567890", invoiceNo, INVOICE_DATE, DUE_DATE,
                List.of(new InvoiceLine("Rulman 6204", new BigDecimal(quantity), new BigDecimal("100.00"), 20)),
                new BigDecimal("150.00"), new BigDecimal("30.00"), new BigDecimal("180.00"), "TRY");
    }

    private UUID document(String status, int version) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status, version, invoice_no,
                                               confidence_score)
                        VALUES (:id, :sha, 'file:///data/documents/x.pdf', :status, :version, 'FTR-1', 0.45)
                        """)
                .param("id", id).param("sha", (id.toString() + id).replace("-", "").substring(0, 64))
                .param("status", status).param("version", version).update();
        invoiceData.save(id, LLM, List.of(), InvoiceDataRepository.Source.LLM);
        return id;
    }

    private void assertUnchanged(UUID id, String status) {
        assertThat(jdbc.sql("SELECT status FROM documents WHERE id = :id").param("id", id).query(String.class)
                .single()).isEqualTo(status);
        assertThat(jdbc.sql("SELECT source FROM invoice_data WHERE document_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("LLM");
        assertThat(transitions(id)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id").param("id", id)
                .query(Long.class).single()).isZero();
    }

    private String outbox(UUID id, String type) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE aggregate_id = :id AND message_type = :type")
                .param("id", id).param("type", type).query(String.class).single();
    }

    private List<String> transitions(UUID id) {
        return jdbc.sql("""
                        SELECT from_status || '>' || to_status || ':' || trigger_event || ':' || actor || ':'
                               || coalesce(reason, '')
                        FROM status_transitions WHERE document_id = :id ORDER BY id
                        """).param("id", id).query(String.class).list();
    }

    /**
     * Eylemin rolündeki kullanıcıyla: onay onaycı, ret kaydın durumuna göre (onayda onaycı, diğerlerinde uzman), geri
     * kalanı uzman. Yetki testleri {@link #callAs} kullanır.
     */
    private ResponseEntity<String> call(HttpMethod method, UUID id, String path, String ifMatch, String body) {
        boolean approverAction = path.equals("/approve") || path.equals("/reject") && "PENDING_APPROVAL".equals(
                jdbc.sql("SELECT status FROM documents WHERE id = :id").param("id", id).query(String.class)
                        .optional().orElse(null));
        return callAs(approverAction ? APPROVER : EXPERT, method, id, path, ifMatch, body);
    }

    private ResponseEntity<String> callAs(String user, HttpMethod method, UUID id, String path, String ifMatch,
            String body) {
        RestClient.RequestBodySpec request = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api/v1/documents/" + id)
                .defaultHeaders(basicAuth(user))
                .defaultStatusHandler(status -> true, (req, response) -> { })
                .build()
                .method(method).uri(path);
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(String.class);
    }
}
