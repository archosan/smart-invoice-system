package com.archosan.invoice.document.api;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.RuleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentQueryIntegrationTest extends DocumentIntegrationTest {

    private static final InvoiceFields FIELDS = new InvoiceFields("ACME A.Ş.", "1234567890", "FTR-1",
            LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 30),
            List.of(new InvoiceLine("Rulman", new BigDecimal("1.5"), new BigDecimal("100.00"), 20)),
            new BigDecimal("150.00"), new BigDecimal("30.00"), new BigDecimal("180.00"), "TRY");

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private InvoiceMessageConverter converter;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void detailContainsFieldsRulesComplianceAndAmountsAsStrings() {
        UUID id = insert("QUEUED_FOR_RPA", "1234567890", "FTR-1", "2026-09-30", 0);
        jdbc.sql("""
                        UPDATE documents SET grand_total = 180.00, currency = 'TRY', confidence_score = 0.92,
                            portal_ref_no = '4711' WHERE id = :id
                        """).param("id", id).update();
        jdbc.sql("""
                        INSERT INTO invoice_data (document_id, fields, rule_results, source)
                        VALUES (:id, CAST(:fields AS jsonb), CAST(:rules AS jsonb), 'LLM')
                        """)
                .param("id", id)
                .param("fields", converter.writeJson(FIELDS))
                .param("rules", converter.writeJson(List.of(new RuleResult("VKN_10_DIGITS", true, null))))
                .update();
        jdbc.sql("""
                        INSERT INTO compliance_results (document_id, result, findings)
                        VALUES (:id, 'COMPLIANT', CAST(:findings AS jsonb))
                        """)
                .param("id", id)
                .param("findings", converter.writeJson(List.of(new ComplianceCompleted.Finding(
                        ComplianceCompleted.Check.UNIT_PRICE, "7.2", "Birim fiyat 100 TL", "100.00", "100.00", true))))
                .update();

        ResponseEntity<String> response = get("/api/v1/documents/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.path("status").asString()).isEqualTo("QUEUED_FOR_RPA");
        assertThat(body.path("portalRefNo").asString()).isEqualTo("4711");
        assertThat(body.path("invoiceDate").asString()).isEqualTo("2026-09-30");
        assertThat(body.path("grandTotal").isString()).isTrue();
        assertThat(body.path("grandTotal").asString()).isEqualTo("180.00");
        assertThat(body.path("confidenceScore").asString()).isEqualTo("0.9200");
        assertThat(body.at("/fields/supplierName").asString()).isEqualTo("ACME A.Ş.");
        assertThat(body.at("/fields/grandTotal").isString()).isTrue();
        assertThat(body.at("/fields/lines/0/quantity").asString()).isEqualTo("1.5");
        assertThat(body.at("/ruleResults/0/rule").asString()).isEqualTo("VKN_10_DIGITS");
        assertThat(body.at("/compliance/result").asString()).isEqualTo("COMPLIANT");
        assertThat(body.at("/compliance/findings/0/clauseNo").asString()).isEqualTo("7.2");
    }

    @Test
    void detailOfFreshRecordHasNullParts() {
        UUID id = insert("RECEIVED", null, null, null, 0);

        JsonNode body = json.readTree(get("/api/v1/documents/" + id).getBody());

        assertThat(body.path("status").asString()).isEqualTo("RECEIVED");
        assertThat(body.path("fields").isNull()).isTrue();
        assertThat(body.path("ruleResults").isNull()).isTrue();
        assertThat(body.path("compliance").isNull()).isTrue();
    }

    @Test
    void unknownDocumentIs404() {
        ResponseEntity<String> response = get("/api/v1/documents/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
    }

    @Test
    void malformedIdIs400() {
        assertThat(get("/api/v1/documents/not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsNewestFirstWithPagination() {
        UUID oldest = insert("RECEIVED", null, null, null, 30);
        UUID middle = insert("RECEIVED", null, null, null, 20);
        UUID newest = insert("RECEIVED", null, null, null, 10);

        JsonNode first = json.readTree(get("/api/v1/documents?size=2").getBody());
        JsonNode second = json.readTree(get("/api/v1/documents?size=2&page=1").getBody());

        assertThat(ids(first)).containsExactly(newest.toString(), middle.toString());
        assertThat(first.path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(ids(second)).containsExactly(oldest.toString());
    }

    @Test
    void filtersByStatusVknAndInvoiceDateRangeInclusive() {
        UUID onFrom = insert("VALIDATED", "1111111111", "A", "2026-09-01", 50);
        UUID inside = insert("VALIDATED", "1111111111", "B", "2026-09-15", 40);
        UUID onTo = insert("NEEDS_REVIEW", "1111111111", "C", "2026-09-30", 30);
        insert("VALIDATED", "1111111111", "D", "2026-10-01", 20);
        insert("VALIDATED", "2222222222", "E", "2026-09-15", 10);
        insert("RECEIVED", null, null, null, 5);

        assertThat(ids(list("?vkn=1111111111&from=2026-09-01&to=2026-09-30")))
                .containsExactly(onTo.toString(), inside.toString(), onFrom.toString());
        assertThat(ids(list("?status=VALIDATED&vkn=1111111111&from=2026-09-01&to=2026-09-30")))
                .containsExactly(inside.toString(), onFrom.toString());
        assertThat(list("?status=VALIDATED").path("totalElements").asLong()).isEqualTo(4);
        assertThat(list("?from=2026-01-01").path("totalElements").asLong()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?status=DONE", "?size=0", "?size=101", "?page=-1", "?from=2026-10-01&to=2026-09-01",
            "?from=30.09.2026"})
    void invalidParametersAre400WithProblemDetail(String query) {
        ResponseEntity<String> response = get("/api/v1/documents" + query);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
    }

    private JsonNode list(String query) {
        ResponseEntity<String> response = get("/api/v1/documents" + query);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(response.getBody());
    }

    private static List<String> ids(JsonNode page) {
        return StreamSupport.stream(page.path("content").spliterator(), false)
                .map(item -> item.path("id").asString())
                .toList();
    }

    private ResponseEntity<String> get(String path) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(basicAuth(EXPERT))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build()
                .get().uri(path)
                .retrieve()
                .toEntity(String.class);
    }

    /** {@code minutesAgo}: sıralamayı belirleyen {@code created_at}. */
    private UUID insert(String status, String vkn, String invoiceNo, String invoiceDate, int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status, supplier_vkn, invoice_no,
                                               invoice_date, created_at)
                        VALUES (:id, :sha, 'file:///x.pdf', :status, :vkn, :invoiceNo, CAST(:invoiceDate AS date),
                                now() - make_interval(mins => :minutes))
                        """)
                .param("id", id)
                .param("sha", (id.toString() + id).replace("-", "").substring(0, 64))
                .param("status", status)
                .param("vkn", vkn)
                .param("invoiceNo", invoiceNo)
                .param("invoiceDate", invoiceDate)
                .param("minutes", minutesAgo)
                .update();
        return id;
    }
}
