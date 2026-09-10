package com.archosan.invoice.compliance.ingest;

import com.archosan.invoice.compliance.ComplianceIntegrationTest;
import com.archosan.invoice.compliance.StubEmbeddingModel;
import com.archosan.invoice.compliance.contract.ContractUploadService;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.IngestContract;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * İndeksleme uçtan uca (B-45, FR-C1): yükleme servisi → outbox → relay → {@code compliance.ingest-contract.q} → gerçek
 * dinleyici; embedding {@link StubEmbeddingModel}'dir. B-44'ün sözleşmeleriyle.
 */
class IngestFlowIntegrationTest extends ComplianceIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Path CONTRACTS = Path.of("src/test/resources/contracts");

    @Autowired
    private ContractUploadService uploads;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;

    @Test
    void contractBecomesReadyWithNormalizedEmbeddedClauseChunks() throws IOException {
        UUID id = upload("contract-02.pdf", "7302918465");

        awaitStatus(id, "READY");

        assertThat(jdbc.sql("SELECT clause_no FROM contract_chunks WHERE contract_id = :id AND clause_no LIKE '4.%' "
                        + "ORDER BY chunk_index").param("id", id).query(String.class).list())
                .containsExactly("4.1", "4.2", "4.3", "4.4", "4.5");
        assertThat(jdbc.sql("""
                        SELECT DISTINCT vector_dims(embedding) || '|' || embedding_model || '|'
                               || round(vector_norm(embedding)::numeric, 3)
                        FROM contract_chunks WHERE contract_id = :id
                        """).param("id", id).query(String.class).list()).containsExactly("1024|bge-m3|1.000");
    }

    /** B-46'nın önkoşulu: kalem başına sorgu, filtreli tam taramayla doğru alt maddeyi bulur. */
    @Test
    void perItemQueryFindsThePriceSubClauseWithinTheContract() throws IOException {
        UUID id = upload("contract-02.pdf", "7302918465");
        UUID other = upload("contract-01.pdf", "4810293756");
        awaitStatus(id, "READY");
        awaitStatus(other, "READY");

        String query = vector("Rulman 6204 ZZ birim fiyat");
        List<String> nearest = jdbc.sql("""
                        SELECT clause_no FROM contract_chunks WHERE contract_id = :id
                        ORDER BY embedding <=> CAST(:q AS vector) LIMIT 4
                        """).param("id", id).param("q", query).query(String.class).list();

        assertThat(nearest).first().isEqualTo("4.1");
    }

    @Test
    void longContractIsStoredWithPartsAndPages() throws IOException {
        UUID id = upload("contract-07.pdf", "9038174652");

        awaitStatus(id, "READY");

        assertThat(jdbc.sql("SELECT max(part) FROM contract_chunks WHERE contract_id = :id AND clause_no = '7'")
                .param("id", id).query(Integer.class).single()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.sql("SELECT max(page) FROM contract_chunks WHERE contract_id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM contract_chunks WHERE contract_id = :id AND content LIKE '%Gizlidir%'")
                .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    void scannedContractFailsWithNoTextAndNoEmbeddingCall() throws IOException {
        UUID id = upload("contract-08.pdf", "8256019374");

        awaitStatus(id, "FAILED");

        assertThat(reason(id)).startsWith("NO_TEXT");
        assertThat(chunkCount(id)).isZero();
        assertThat(EMBEDDINGS.calls()).isZero();
    }

    /** Ollama'ya ulaşılamıyor: geçici hata, teslim limitinden sonra DLQ → FAILED; yarım chunk yazılmaz. */
    @Test
    void persistentEmbeddingFailureEndsInDlqAndFailed() throws IOException {
        EMBEDDINGS.failWith(new IllegalStateException("Ollama'ya ulaşılamıyor"));

        UUID id = upload("contract-01.pdf", "4810293756");

        awaitStatus(id, "FAILED");
        assertThat(reason(id)).startsWith("DLQ: delivery_limit").contains("compliance.ingest-contract.q");
        assertThat(chunkCount(id)).isZero();
        assertThat(EMBEDDINGS.calls()).isGreaterThanOrEqualTo(3);
    }

    /** Yanlış model (boyut ≠ 1024) kalıcı hatadır: yeniden denemeden DLQ → FAILED. */
    @Test
    void wrongDimensionsGoStraightToDlq() throws IOException {
        EMBEDDINGS.dimensions(768);

        UUID id = upload("contract-01.pdf", "4810293756");

        awaitStatus(id, "FAILED");
        assertThat(reason(id)).startsWith("DLQ: rejected");
        assertThat(EMBEDDINGS.calls()).isEqualTo(1);
    }

    @Test
    void redeliveredCommandChangesNothing() throws IOException {
        UUID id = upload("contract-01.pdf", "4810293756");
        awaitStatus(id, "READY");
        long chunks = chunkCount(id);
        int calls = EMBEDDINGS.calls();
        String storageUri = jdbc.sql("SELECT storage_uri FROM contracts WHERE id = :id").param("id", id)
                .query(String.class).single();

        // Başka bir mesaj kimliğiyle aynı komut (ör. yeniden yükleme yarışı): sözleşme INGESTING değil, atlanır.
        rabbitTemplate.send("invoice.commands", "contract.ingest", converter.toAmqpMessage(
                MessageEnvelope.of(UUID.randomUUID(), id, MessageType.INGEST_CONTRACT),
                new IngestContract(id, storageUri)));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> chunkCount(id) == chunks && "READY".equals(status(id)));
        assertThat(EMBEDDINGS.calls()).isEqualTo(calls);
    }

    private UUID upload(String file, String vkn) throws IOException {
        try (InputStream in = Files.newInputStream(CONTRACTS.resolve(file))) {
            return uploads.upload(in, vkn, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), EXPERT).contractId();
        }
    }

    private static String vector(String text) {
        float[] raw = StubEmbeddingModel.vector(text, StubEmbeddingModel.DIMENSIONS);
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float v : raw) {
            joiner.add(Float.toString(v));
        }
        return joiner.toString();
    }

    private void awaitStatus(UUID id, String expected) {
        await().atMost(TIMEOUT).until(() -> expected.equals(status(id)));
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM contracts WHERE id = :id").param("id", id).query(String.class).single();
    }

    private String reason(UUID id) {
        return jdbc.sql("SELECT failure_reason FROM contracts WHERE id = :id").param("id", id).query(String.class)
                .single();
    }

    private long chunkCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM contract_chunks WHERE contract_id = :id").param("id", id)
                .query(Long.class).single();
    }
}
