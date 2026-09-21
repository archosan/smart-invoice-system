package com.archosan.invoice.document.events;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.document.persistence.InvoiceDataRepository;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.message.ExtractionCompleted;
import com.archosan.invoice.messaging.message.ExtractionFailed;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.message.RpaCompleted;
import com.archosan.invoice.messaging.message.RuleResult;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Olaylar outbox + relay ile gerçek kuyruklara gider, gerçek dinleyiciler işler (B-12). document-service'in kendi
 * yayınladığı komutların tüketicisi yoktur; testler outbox'taki gövdelerine bakar.
 */
class EventFlowIntegrationTest extends DocumentIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 9, 30);
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 10, 30);
    private static final List<InvoiceLine> LINES =
            List.of(new InvoiceLine("Rulman 6204", new BigDecimal("1.5"), new BigDecimal("100.00"), 20));
    private static final InvoiceFields FIELDS = new InvoiceFields("ACME A.Ş.", "1234567890", "FTR-2026-001",
            INVOICE_DATE, DUE_DATE, LINES, new BigDecimal("150.00"), new BigDecimal("30.00"),
            new BigDecimal("180.00"), "TRY");

    @Autowired
    private OutboxWriter outbox;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private InvoiceDataRepository invoiceData;

    @BeforeEach
    void setUp() {
        for (String queue : List.of("extraction.extract-invoice.q", "compliance.check-compliance.q",
                "rpa.post-to-portal.q", "document.compliance-events.dlq", "document.extraction-events.dlq")) {
            amqpAdmin.purgeQueue(queue, false);
        }
    }

    @Test
    void happyPathEndsInPostedWithCommandsInOutbox() {
        UUID id = insertDocument(DocumentStatus.RECEIVED);

        publish(id, extractionCompleted(id, "0.92"));
        awaitStatus(id, "VALIDATED");

        assertThat(jdbc.sql("""
                        SELECT supplier_vkn || '|' || invoice_no || '|' || invoice_date || '|' || grand_total || '|'
                               || currency || '|' || confidence_score
                        FROM documents WHERE id = :id
                        """).param("id", id).query(String.class).single())
                .isEqualTo("1234567890|FTR-2026-001|2026-09-30|180.00|TRY|0.9200");
        assertThat(jdbc.sql("""
                        SELECT (fields->>'grandTotal') || '|' || jsonb_typeof(fields->'grandTotal') || '|' || source
                               || '|' || (rule_results->0->>'rule')
                        FROM invoice_data WHERE document_id = :id
                        """).param("id", id).query(String.class).single())
                .isEqualTo("180.00|string|LLM|LINES_SUM_EQUALS_SUBTOTAL");
        assertThat(outboxPayload(id, "CheckCompliance", CheckCompliance.class)).isEqualTo(new CheckCompliance(id,
                "1234567890", INVOICE_DATE, DUE_DATE, LINES, new BigDecimal("150.00"), new BigDecimal("30.00"),
                new BigDecimal("180.00"), "TRY"));

        publish(id, new ComplianceCompleted(id, ComplianceCompleted.Result.COMPLIANT, null, List.of()));
        awaitStatus(id, "QUEUED_FOR_RPA");

        assertThat(jdbc.sql("SELECT result FROM compliance_results WHERE document_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("COMPLIANT");
        assertThat(outboxPayload(id, "PostToPortal", PostToPortal.class)).isEqualTo(new PostToPortal(id,
                "1234567890", "ACME A.Ş.", "FTR-2026-001", INVOICE_DATE, DUE_DATE, new BigDecimal("180.00"),
                new BigDecimal("30.00"), "TRY"));

        publish(id, new RpaCompleted(id, "4711", false));
        awaitStatus(id, "POSTED");

        assertThat(jdbc.sql("SELECT portal_ref_no FROM documents WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("4711");
        assertThat(transitions(id)).containsExactly(
                "RECEIVED>EXTRACTED:ExtractionCompleted:extraction-service:",
                "EXTRACTED>VALIDATED:ExtractionCompleted:document-service:güven 0.92 ≥ eşik 0.80",
                "VALIDATED>COMPLIANCE_CHECKED:ComplianceCompleted:compliance-service:",
                "COMPLIANCE_CHECKED>QUEUED_FOR_RPA:ComplianceCompleted:document-service:tutar 180.00 ≤ eşik 100000.00",
                "QUEUED_FOR_RPA>POSTED:RpaCompleted:rpa-service:");
        assertThat(count("dead_letters")).isZero();
    }

    @Test
    void lowConfidenceGoesToReviewWithFieldsStoredAndNoCommand() {
        UUID id = insertDocument(DocumentStatus.RECEIVED);

        publish(id, extractionCompleted(id, "0.50"));
        awaitStatus(id, "NEEDS_REVIEW");

        assertThat(transitions(id)).last().isEqualTo(
                "EXTRACTED>NEEDS_REVIEW:ExtractionCompleted:document-service:güven 0.50 < eşik 0.80");
        assertThat(count("invoice_data")).isEqualTo(1);
        assertThat(outboxCount(id, "CheckCompliance")).isZero();
    }

    @Test
    void extractionFailureGoesToReviewWithReason() {
        UUID id = insertDocument(DocumentStatus.RECEIVED);

        publish(id, new ExtractionFailed(id, ExtractionFailed.Reason.LLM_RETRIES_EXHAUSTED, "zaman aşımı", 3));
        awaitStatus(id, "NEEDS_REVIEW");

        assertThat(transitions(id)).containsExactly(
                "RECEIVED>NEEDS_REVIEW:ExtractionFailed:extraction-service:LLM_RETRIES_EXHAUSTED: zaman aşımı");
    }

    @Test
    void lateEventIsRecordedAndDoesNotChangeStatus() {
        UUID id = insertDocument(DocumentStatus.POSTED);

        publish(id, extractionCompleted(id, "0.92"));

        await().atMost(TIMEOUT).until(() -> count("dead_letters") == 1);
        assertThat(jdbc.sql("SELECT kind || ':' || source_queue || ':' || message_type FROM dead_letters")
                .query(String.class).single())
                .isEqualTo("LATE_EVENT:document.extraction-events.q:ExtractionCompleted");
        assertThat(status(id)).isEqualTo("POSTED");
    }

    @Test
    void redeliveredEventHasNoSecondEffect() {
        UUID id = insertDocument(DocumentStatus.RECEIVED);
        UUID messageId = publish(id, extractionCompleted(id, "0.92"));
        awaitStatus(id, "VALIDATED");

        // Relay'in aynı mesajı tekrar yayınlaması (ör. ack'ten sonra published_at yazılamadan çökme).
        jdbc.sql("UPDATE outbox SET published_at = NULL WHERE id = :id").param("id", messageId).update();
        await().atMost(TIMEOUT).until(() -> jdbc.sql("SELECT published_at IS NOT NULL FROM outbox WHERE id = :id")
                .param("id", messageId).query(Boolean.class).single());

        // Inbox olmasa ikinci kopya STALE olur ve LATE_EVENT yazılırdı.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> count("dead_letters") == 0 && transitions(id).size() == 2);
        assertThat(outboxCount(id, "CheckCompliance")).isEqualTo(1);
    }

    /** B-46, US-06: uyumsuz sonuç onaya gider; bulgular kayıtta, komut yok. */
    @Test
    void nonCompliantResultWaitsForApprovalWithFindings() {
        UUID id = insertDocument(DocumentStatus.VALIDATED);
        List<ComplianceCompleted.Finding> findings = List.of(
                new ComplianceCompleted.Finding(ComplianceCompleted.Check.UNIT_PRICE, "4.1",
                        "4.1. Rulman 6204 ZZ için birim fiyat, adet başına 80,00 TL'dir (KDV hariç).",
                        "Rulman 6204 ZZ: 86.50", "80.00 / adet", true),
                new ComplianceCompleted.Finding(ComplianceCompleted.Check.PAYMENT_TERM, null, null, "30 gün", null,
                        false));

        publish(id, new ComplianceCompleted(id, ComplianceCompleted.Result.NON_COMPLIANT, UUID.randomUUID(),
                findings));
        awaitStatus(id, "PENDING_APPROVAL");

        assertThat(transitions(id)).containsExactly("VALIDATED>PENDING_APPROVAL:ComplianceCompleted:compliance-service:"
                + "NON_COMPLIANT: UNIT_PRICE (Madde 4.1), PAYMENT_TERM güvenilmez");
        assertThat(jdbc.sql("SELECT result || '|' || jsonb_array_length(findings) FROM compliance_results "
                + "WHERE document_id = :id").param("id", id).query(String.class).single()).isEqualTo("NON_COMPLIANT|2");
        assertThat(outboxCount(id, "PostToPortal")).isZero();
    }

    /** B-46, US-07: sözleşme yok ya da çakışıyor da insana gider. */
    @Test
    void missingOrConflictingContractWaitsForApproval() {
        UUID none = insertDocument(DocumentStatus.VALIDATED);
        UUID conflict = insertDocument(DocumentStatus.VALIDATED);

        publish(none, new ComplianceCompleted(none, ComplianceCompleted.Result.NO_CONTRACT, null, List.of()));
        publish(conflict, new ComplianceCompleted(conflict, ComplianceCompleted.Result.CONTRACT_CONFLICT, null,
                List.of()));

        awaitStatus(none, "PENDING_APPROVAL");
        awaitStatus(conflict, "PENDING_APPROVAL");
        assertThat(transitions(none)).last().asString().endsWith(":compliance-service:NO_CONTRACT");
        assertThat(transitions(conflict)).last().asString().endsWith(":compliance-service:CONTRACT_CONFLICT");
    }

    @Test
    void compliantInvoiceOverAmountThresholdWaitsForApprovalWithoutCommand() {
        UUID id = insertDocument(DocumentStatus.VALIDATED);
        invoiceData.save(id, withTotal(new BigDecimal("100000.01"), "TRY"), List.of(), InvoiceDataRepository.Source.LLM);

        publish(id, new ComplianceCompleted(id, ComplianceCompleted.Result.COMPLIANT, null, List.of()));
        awaitStatus(id, "PENDING_APPROVAL");

        assertThat(transitions(id)).containsExactly(
                "VALIDATED>PENDING_APPROVAL:ComplianceCompleted:document-service:tutar 100000.01 > eşik 100000.00");
        assertThat(jdbc.sql("SELECT result FROM compliance_results WHERE document_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("COMPLIANT");
        assertThat(outboxCount(id, "PostToPortal")).isZero();
    }

    @Test
    void compliantInvoiceInForeignCurrencyAlwaysWaitsForApproval() {
        UUID id = insertDocument(DocumentStatus.VALIDATED);
        invoiceData.save(id, withTotal(new BigDecimal("10.00"), "USD"), List.of(), InvoiceDataRepository.Source.LLM);

        publish(id, new ComplianceCompleted(id, ComplianceCompleted.Result.COMPLIANT, null, List.of()));
        awaitStatus(id, "PENDING_APPROVAL");

        assertThat(transitions(id)).containsExactly(
                "VALIDATED>PENDING_APPROVAL:ComplianceCompleted:document-service:para birimi USD ≠ TRY");
        assertThat(outboxCount(id, "PostToPortal")).isZero();
    }

    @Test
    void secondDocumentOfSameInvoiceIsSuspectedDuplicateWithoutCommand() {
        UUID first = insertDocument(DocumentStatus.POSTED, " 1234567890", "ftr-2026-001 ");
        UUID id = insertDocument(DocumentStatus.RECEIVED);

        publish(id, extractionCompleted(id, "0.92"));
        awaitStatus(id, "DUPLICATE_SUSPECTED");

        assertThat(transitions(id)).containsExactly(
                "RECEIVED>EXTRACTED:ExtractionCompleted:extraction-service:",
                "EXTRACTED>DUPLICATE_SUSPECTED:ExtractionCompleted:document-service:mükerrer şüphesi: " + first
                        + " (POSTED)");
        assertThat(outboxCount(id, "CheckCompliance")).isZero();
    }

    @Test
    void rejectedDocumentDoesNotMakeSuspicion() {
        insertDocument(DocumentStatus.REJECTED, "1234567890", "FTR-2026-001");
        UUID id = insertDocument(DocumentStatus.RECEIVED);

        publish(id, extractionCompleted(id, "0.92"));

        awaitStatus(id, "VALIDATED");
    }

    @Test
    void lowConfidenceWinsOverDuplicate() {
        insertDocument(DocumentStatus.POSTED, "1234567890", "FTR-2026-001");
        UUID id = insertDocument(DocumentStatus.RECEIVED);

        publish(id, extractionCompleted(id, "0.50"));

        awaitStatus(id, "NEEDS_REVIEW");
    }

    private static InvoiceFields withTotal(BigDecimal grandTotal, String currency) {
        return new InvoiceFields(FIELDS.supplierName(), FIELDS.supplierVkn(), FIELDS.invoiceNo(), FIELDS.invoiceDate(),
                FIELDS.dueDate(), FIELDS.lines(), FIELDS.subtotal(), FIELDS.vatTotal(), grandTotal, currency);
    }

    private static ExtractionCompleted extractionCompleted(UUID id, String score) {
        return new ExtractionCompleted(id, FIELDS, new BigDecimal(score),
                List.of(new RuleResult("LINES_SUM_EQUALS_SUBTOTAL", true, null)), "test-model", "v1");
    }

    private UUID publish(UUID documentId, InvoiceMessage event) {
        return new TransactionTemplate(transactionManager).execute(status -> outbox.add(documentId, event));
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(TIMEOUT).until(() -> expected.equals(status(id)));
    }

    private UUID insertDocument(DocumentStatus status) {
        return insertDocument(status, null, null);
    }

    private UUID insertDocument(DocumentStatus status, String vkn, String invoiceNo) {
        UUID id = UUID.randomUUID();
        String sha = (id.toString() + id).replace("-", "").substring(0, 64);
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status, supplier_vkn, invoice_no)
                        VALUES (:id, :sha, 'file:///data/documents/x.pdf', :status, :vkn, :invoiceNo)
                        """)
                .param("id", id).param("sha", sha).param("status", status.name()).param("vkn", vkn)
                .param("invoiceNo", invoiceNo).update();
        return id;
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM documents WHERE id = :id").param("id", id).query(String.class).single();
    }

    private List<String> transitions(UUID id) {
        return jdbc.sql("""
                        SELECT from_status || '>' || to_status || ':' || trigger_event || ':' || actor || ':'
                               || coalesce(reason, '')
                        FROM status_transitions WHERE document_id = :id ORDER BY id
                        """).param("id", id).query(String.class).list();
    }

    private <T> T outboxPayload(UUID id, String type, Class<T> payloadType) {
        String payload = jdbc.sql("SELECT payload::text FROM outbox WHERE aggregate_id = :id AND message_type = :type")
                .param("id", id).param("type", type).query(String.class).single();
        return converter.readJson(payload, payloadType);
    }

    private long outboxCount(UUID id, String type) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND message_type = :type")
                .param("id", id).param("type", type).query(Long.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
