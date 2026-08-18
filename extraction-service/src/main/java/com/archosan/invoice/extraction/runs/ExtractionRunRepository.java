package com.archosan.invoice.extraction.runs;

import com.archosan.invoice.extraction.llm.LlmAttempt;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code extraction_runs} (FR-E6, B-21): her LLM denemesi bir satır, ham yanıtla. Sonuçla ve inbox kaydıyla aynı
 * transaction'da yazılır.
 */
@Repository
public class ExtractionRunRepository {

    private final JdbcClient jdbc;

    public ExtractionRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(UUID documentId, String model, String promptVersion, List<LlmAttempt> attempts) {
        for (LlmAttempt attempt : attempts) {
            jdbc.sql("""
                            INSERT INTO extraction_runs
                                (document_id, attempt_no, model, prompt_version, raw_output, parse_error, duration_ms)
                            VALUES (:documentId, :attemptNo, :model, :promptVersion, :raw, :error, :durationMs)
                            """)
                    .param("documentId", documentId)
                    .param("attemptNo", attempt.attemptNo())
                    .param("model", model)
                    .param("promptVersion", promptVersion)
                    .param("raw", attempt.rawOutput())
                    .param("error", attempt.error())
                    .param("durationMs", (int) Math.min(Integer.MAX_VALUE, attempt.duration().toMillis()))
                    .update();
        }
    }

    /** Bir belgenin bütün denemeleri, sırayla (B-47 inceleme API'si). */
    public List<ExtractionRun> findByDocument(UUID documentId) {
        return jdbc.sql("""
                        SELECT document_id, attempt_no, model, prompt_version, raw_output, parse_error, duration_ms,
                               created_at, raw_output_purged_at
                        FROM extraction_runs WHERE document_id = :documentId ORDER BY attempt_no
                        """)
                .param("documentId", documentId)
                .query((rs, n) -> new ExtractionRun(rs.getObject("document_id", UUID.class), rs.getInt("attempt_no"),
                        rs.getString("model"), rs.getString("prompt_version"), rs.getString("raw_output"),
                        rs.getString("parse_error"), (Integer) rs.getObject("duration_ms"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("raw_output_purged_at", OffsetDateTime.class)))
                .list();
    }

    /**
     * {@code before}'dan eski denemelerin ham çıktısını siler, satırı tutar (B-47).
     *
     * @return ham çıktısı silinen satır sayısı
     */
    public int purgeRawOutputBefore(OffsetDateTime before) {
        return jdbc.sql("""
                        UPDATE extraction_runs SET raw_output = NULL, raw_output_purged_at = now()
                        WHERE raw_output IS NOT NULL AND created_at < :before
                        """)
                .param("before", before)
                .update();
    }

    /**
     * Bir LLM denemesi. {@code rawOutput} boşsa ya zaman aşımıdır ya da saklama süresi dolmuştur
     * ({@code rawOutputPurgedAt} dolu).
     */
    public record ExtractionRun(UUID documentId, int attemptNo, String model, String promptVersion, String rawOutput,
            String parseError, Integer durationMs, OffsetDateTime createdAt, OffsetDateTime rawOutputPurgedAt) {
    }
}
