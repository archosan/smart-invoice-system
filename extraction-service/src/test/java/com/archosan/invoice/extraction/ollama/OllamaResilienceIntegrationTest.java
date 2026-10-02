package com.archosan.invoice.extraction.ollama;

import com.archosan.invoice.extraction.ExtractionIntegrationTest;
import com.archosan.invoice.extraction.llm.OllamaAvailability;
import com.archosan.invoice.extraction.testdata.PrintedInvoices;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.ExtractionCompleted;
import com.archosan.invoice.messaging.message.ExtractionFailed;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Spring AI'ın gerçek {@code OllamaChatModel}'i, Ollama'yı taklit eden HTTP sunucusuna karşı (B-19): şema ve sıcaklığın
 * isteğe yazılması, geri beslemeli yeniden deneme, okuma zaman aşımı ve Ollama'ya ulaşılamaması (A1).
 */
class OllamaResilienceIntegrationTest extends ExtractionIntegrationTest {

    private static final FakeOllamaServer OLLAMA = new FakeOllamaServer();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String QUEUE = "extraction.extract-invoice.q";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        OLLAMA.start();
    }

    @DynamicPropertySource
    static void fakeOllama(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", OLLAMA::baseUrl);
        registry.add("invoice.extraction.llm.timeout", () -> "1s");
        registry.add("invoice.extraction.llm.availability-probe-interval", () -> "300ms");
    }

    @AfterAll
    static void stopServer() {
        OLLAMA.stop();
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    @Qualifier("extractInvoiceContainer")
    private SimpleMessageListenerContainer container;
    @Autowired
    private OllamaAvailability availability;

    @BeforeEach
    void reset() {
        OLLAMA.reset();
        OLLAMA.start();
        amqpAdmin.purgeQueue(QUEUE, false);
    }

    @Test
    void requestCarriesSchemaZeroTemperatureAndModel() throws Exception {
        OLLAMA.always(new FakeOllamaServer.Reply(Duration.ZERO, OllamaResilienceIntegrationTest::goodAnswer));

        UUID documentId = send("invoice-02.pdf");

        ExtractionCompleted completed = awaitEvent(documentId, "ExtractionCompleted", ExtractionCompleted.class);
        assertThat(completed.fields()).isEqualTo(SyntheticInvoices.all().get(1).fields());
        var request = JSON.readTree(OLLAMA.chatRequests().getFirst());
        assertThat(request.path("model").asString()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(request.at("/options/temperature").asDouble()).isZero();
        assertThat(request.at("/format/properties/supplierVkn/type").asString()).isEqualTo("string");
        assertThat(request.at("/messages/0/role").asString()).isEqualTo("system");
    }

    @Test
    void badOutputIsRetriedWithFeedbackAndThenSucceeds() throws Exception {
        OLLAMA.then(FakeOllamaServer.Reply.of("Elbette! {bozuk"));
        OLLAMA.always(new FakeOllamaServer.Reply(Duration.ZERO, OllamaResilienceIntegrationTest::goodAnswer));

        UUID documentId = send("invoice-01.pdf");

        awaitEvent(documentId, "ExtractionCompleted", ExtractionCompleted.class);
        assertThat(OLLAMA.chatRequests()).hasSize(2);
        String retry = OLLAMA.chatRequests().get(1);
        assertThat(retry).contains("Önceki yanıtın geçerli değildi", "Elbette! {bozuk");
    }

    @Test
    void threeBadOutputsExhaustTheBudget() throws Exception {
        OLLAMA.always(FakeOllamaServer.Reply.of("yine bozuk"));

        UUID documentId = send("invoice-01.pdf");

        ExtractionFailed failed = awaitEvent(documentId, "ExtractionFailed", ExtractionFailed.class);
        assertThat(failed.reason()).isEqualTo(ExtractionFailed.Reason.LLM_RETRIES_EXHAUSTED);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(OLLAMA.chatRequests()).hasSize(3);
    }

    @Test
    void readTimeoutCountsAsAttempt() throws Exception {
        // read-timeout 1 sn (llm.timeout); her yanıt 2 sn gecikir.
        OLLAMA.always(new FakeOllamaServer.Reply(Duration.ofSeconds(2), OllamaResilienceIntegrationTest::goodAnswer));

        long start = System.nanoTime();
        UUID documentId = send("invoice-01.pdf");

        ExtractionFailed failed = awaitEvent(documentId, "ExtractionFailed", ExtractionFailed.class);
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertThat(failed.attempts()).isEqualTo(3);
        assertThat(failed.detail()).contains("zaman aşımı");
        // Deneme başına tek istek ve ~1 sn: Spring AI'ın gizli yeniden denemesi (spring.ai.retry) devrede değil.
        assertThat(OLLAMA.chatRequests()).hasSize(3);
        assertThat(took).isLessThan(Duration.ofSeconds(8));
    }

    @Test
    void unreachableOllamaPausesListenerAndResumesWithoutSpendingTheBudget() throws Exception {
        OLLAMA.stop();

        UUID documentId = send("invoice-01.pdf");

        await().atMost(TIMEOUT).until(() -> !container.isRunning() && !availability.isAvailable());
        assertThat(availability.health().getStatus()).isEqualTo(Status.UNKNOWN);
        // Mesaj kuyruğa geri kondu ve dokunulmadan bekliyor; olay yazılmadı.
        await().atMost(TIMEOUT).until(() -> amqpAdmin.getQueueInfo(QUEUE).getMessageCount() == 1);
        assertThat(outboxCount(documentId)).isZero();

        OLLAMA.always(new FakeOllamaServer.Reply(Duration.ZERO, OllamaResilienceIntegrationTest::goodAnswer));
        OLLAMA.start();

        awaitEvent(documentId, "ExtractionCompleted", ExtractionCompleted.class);
        assertThat(container.isRunning()).isTrue();
        assertThat(availability.health().getStatus()).isEqualTo(Status.UP);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE message_type = 'ExtractionFailed'")
                .query(Long.class).single()).isZero();
        // Ulaşılamama deneme sayılmaz: yalnızca Ollama dönünce yapılan başarılı deneme kayıtlı.
        assertThat(jdbc.sql("SELECT attempt_no || ':' || coalesce(parse_error, '') FROM extraction_runs "
                + "WHERE document_id = :id").param("id", documentId).query(String.class).list())
                .containsExactly("1:");
    }

    /** İsteğin içindeki fatura metnine göre, faturada basılı değerlerle iyi bir yanıt. */
    private static String goodAnswer(String requestBody) {
        return PrintedInvoices.findByText(requestBody)
                .map(invoice -> JSON.writeValueAsString(PrintedInvoices.asPrinted(invoice)))
                .orElse("{}");
    }

    private UUID send(String file) throws Exception {
        UUID documentId = UUID.randomUUID();
        Path pdf = INVOICES.resolve(file);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pdf)));
        rabbitTemplate.send("invoice.commands", "extract.invoice", converter.toAmqpMessage(
                MessageEnvelope.of(UUID.randomUUID(), documentId, MessageType.EXTRACT_INVOICE),
                new ExtractInvoice(documentId, pdf.toUri().toString(), sha)));
        return documentId;
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
