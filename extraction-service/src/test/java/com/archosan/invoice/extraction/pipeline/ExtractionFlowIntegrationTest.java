package com.archosan.invoice.extraction.pipeline;

import com.archosan.invoice.extraction.ExtractionIntegrationTest;
import com.archosan.invoice.extraction.testdata.StubChatModel;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.ExtractionCompleted;
import com.archosan.invoice.messaging.message.ExtractionFailed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code ExtractInvoice} gerçek kuyruğa gönderilir, gerçek dinleyici işler; LLM {@link StubChatModel}'dir ve B-16
 * setinin basılı değerlerini döndürür. Outbox'taki olay B-16'nın beklenen alanlarıyla, {@code extraction_runs}
 * denemelerle karşılaştırılır.
 */
@Import(ExtractionFlowIntegrationTest.StubLlmConfiguration.class)
class ExtractionFlowIntegrationTest extends ExtractionIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String DLQ = "extraction.extract-invoice.dlq";

    static final StubChatModel LLM = new StubChatModel();

    @TestConfiguration(proxyBeanMethods = false)
    static class StubLlmConfiguration {

        @Bean
        @Primary
        ChatModel stubChatModel() {
            return LLM;
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void goodLlm() {
        amqpAdmin.purgeQueue(DLQ, false);
        LLM.reset();
    }

    static List<SyntheticInvoice> invoices() {
        return SyntheticInvoices.all();
    }

    @ParameterizedTest
    @MethodSource("invoices")
    void eachSyntheticInvoiceProducesExpectedEvent(SyntheticInvoice invoice) throws Exception {
        UUID documentId = send(invoice.file());

        if (invoice.expectedOutcome() == SyntheticInvoice.ExpectedOutcome.NO_TEXT) {
            ExtractionFailed failed = awaitEvent(documentId, "ExtractionFailed", ExtractionFailed.class);
            assertThat(failed.reason()).isEqualTo(ExtractionFailed.Reason.NO_TEXT);
            assertThat(failed.attempts()).isZero();
            assertThat(failed.detail()).contains("metin katmanı yok");
            // LLM çağrılmadı, deneme kaydı yok.
            assertThat(runs(documentId)).isEmpty();
        } else {
            ExtractionCompleted completed = awaitEvent(documentId, "ExtractionCompleted", ExtractionCompleted.class);
            // Basılı değerler (1.234,56 TL, 18/09/2026, %20, ₺) ayrıştırıcıdan geçip beklenen alanlara dönmeli.
            assertThat(completed.fields()).isEqualTo(invoice.fields());
            assertThat(completed.model()).isEqualTo("qwen2.5:7b-instruct");
            assertThat(completed.promptVersion()).isEqualTo("v3");
            assertThat(completed.ruleResults()).hasSize(7);
            if (invoice.expectedOutcome() == SyntheticInvoice.ExpectedOutcome.POSTED) {
                assertThat(completed.confidenceScore()).isEqualByComparingTo("1.00");
                assertThat(completed.ruleResults()).allSatisfy(r -> assertThat(r.passed()).isTrue());
            } else {
                // 9. fatura: varsayılan eşiğin (0,80) altında → document-service NEEDS_REVIEW.
                assertThat(completed.confidenceScore()).isEqualByComparingTo("0.45");
                assertThat(completed.ruleResults()).filteredOn(r -> !r.passed()).extracting(r -> r.rule())
                        .containsExactlyInAnyOrder("LINES_SUM_EQUALS_SUBTOTAL", "VAT_MATCHES_LINES");
            }
            assertThat(runs(documentId)).singleElement().satisfies(run -> {
                assertThat(run.attemptNo()).isEqualTo(1);
                assertThat(run.model()).isEqualTo("qwen2.5:7b-instruct");
                assertThat(run.promptVersion()).isEqualTo("v3");
                assertThat(run.rawOutput()).contains(invoice.fields().invoiceNo());
                assertThat(run.parseError()).isNull();
                assertThat(run.durationMs()).isNotNegative();
            });
        }
    }

    @Test
    void unparsableLlmOutputIsExtractionFailedAfterAllAttemptsWithEveryAttemptRecorded() throws Exception {
        LLM.always(StubChatModel.answer("Elbette, işte JSON: {bozuk"));

        UUID documentId = send("invoice-01.pdf");

        ExtractionFailed failed = awaitEvent(documentId, "ExtractionFailed", ExtractionFailed.class);
        assertThat(failed.reason()).isEqualTo(ExtractionFailed.Reason.LLM_RETRIES_EXHAUSTED);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(runs(documentId)).extracting(Run::attemptNo).containsExactly(1, 2, 3);
        assertThat(runs(documentId)).allSatisfy(run -> {
            assertThat(run.rawOutput()).isEqualTo("Elbette, işte JSON: {bozuk");
            assertThat(run.parseError()).startsWith("LLM yanıtı çözülemedi");
        });
    }

    @Test
    void badThenGoodOutputRecordsBothAttempts() throws Exception {
        LLM.then(StubChatModel.answer("{bozuk"));

        UUID documentId = send("invoice-01.pdf");

        awaitEvent(documentId, "ExtractionCompleted", ExtractionCompleted.class);
        List<Run> runs = runs(documentId);
        assertThat(runs).extracting(Run::attemptNo).containsExactly(1, 2);
        assertThat(runs.get(0).parseError()).isNotNull();
        assertThat(runs.get(1).parseError()).isNull();
        // İkinci istekte geri besleme vardı.
        assertThat(LLM.prompts()).hasSize(2);
        assertThat(LLM.prompts().get(1).getInstructions().getLast().getText()).contains("Önceki yanıtın geçerli değildi");
    }

    @Test
    void timeoutIsRecordedWithoutRawOutput() throws Exception {
        LLM.then(StubChatModel.TIMEOUT);

        UUID documentId = send("invoice-01.pdf");

        awaitEvent(documentId, "ExtractionCompleted", ExtractionCompleted.class);
        Run first = runs(documentId).getFirst();
        assertThat(first.rawOutput()).isNull();
        assertThat(first.parseError()).contains("zaman aşımı");
    }

    @Test
    void hashMismatchIsDeadLetteredWithoutCallingLlm() {
        UUID documentId = UUID.randomUUID();
        String uri = INVOICES.resolve("invoice-01.pdf").toUri().toString();

        rabbitTemplate.send("invoice.commands", "extract.invoice", converter.toAmqpMessage(
                MessageEnvelope.of(UUID.randomUUID(), documentId, MessageType.EXTRACT_INVOICE),
                new ExtractInvoice(documentId, uri, "0".repeat(64))));

        Message deadLettered = rabbitTemplate.receive(DLQ, TIMEOUT.toMillis());
        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-first-death-reason"))
                .isEqualTo("rejected");
        assertThat(LLM.prompts()).isEmpty();
        assertThat(outboxCount(documentId)).isZero();
    }

    @Test
    void redeliveredCommandProducesOneEventAndOneSetOfRuns() throws Exception {
        UUID documentId = UUID.randomUUID();
        Message message = command(documentId, "invoice-01.pdf");

        rabbitTemplate.send("invoice.commands", "extract.invoice", message);
        awaitEvent(documentId, "ExtractionCompleted", ExtractionCompleted.class);
        rabbitTemplate.send("invoice.commands", "extract.invoice", message);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> outboxCount(documentId) == 1 && runs(documentId).size() == 1);
    }

    record Run(int attemptNo, String model, String promptVersion, String rawOutput, String parseError,
               int durationMs) {
    }

    private List<Run> runs(UUID documentId) {
        return jdbc.sql("""
                        SELECT attempt_no, model, prompt_version, raw_output, parse_error, duration_ms
                        FROM extraction_runs WHERE document_id = :id ORDER BY attempt_no
                        """)
                .param("id", documentId)
                .query(Run.class)
                .list();
    }

    private UUID send(String file) throws Exception {
        UUID documentId = UUID.randomUUID();
        rabbitTemplate.send("invoice.commands", "extract.invoice", command(documentId, file));
        return documentId;
    }

    private Message command(UUID documentId, String file) throws Exception {
        Path pdf = INVOICES.resolve(file);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pdf)));
        return converter.toAmqpMessage(MessageEnvelope.of(UUID.randomUUID(), documentId, MessageType.EXTRACT_INVOICE),
                new ExtractInvoice(documentId, pdf.toUri().toString(), sha));
    }

    private <T> T awaitEvent(UUID documentId, String type, Class<T> payloadType) {
        await().atMost(TIMEOUT).until(() -> jdbc.sql(
                        "SELECT count(*) FROM outbox WHERE aggregate_id = :id AND message_type = :type")
                .param("id", documentId).param("type", type).query(Long.class).single() == 1);
        String payload = jdbc.sql("SELECT payload::text FROM outbox WHERE aggregate_id = :id AND message_type = :type")
                .param("id", documentId).param("type", type).query(String.class).single();
        return converter.readJson(payload, payloadType);
    }

    private long outboxCount(UUID documentId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id").param("id", documentId)
                .query(Long.class).single();
    }
}
