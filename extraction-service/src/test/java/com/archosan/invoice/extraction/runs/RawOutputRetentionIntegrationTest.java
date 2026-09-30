package com.archosan.invoice.extraction.runs;

import com.archosan.invoice.extraction.ExtractionIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** B-47: saklama süresi dolan denemenin yalnız ham çıktısı silinir; satır, model ve hata kalır. */
@TestPropertySource(properties = "invoice.extraction.retention.raw-output=30d")
class RawOutputRetentionIntegrationTest extends ExtractionIntegrationTest {

    @Autowired
    private RawOutputRetention retention;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void purgesOnlyRawOutputOfExpiredAttempts() {
        UUID documentId = UUID.randomUUID();
        insert(documentId, 1, "eski yanıt", "31 days");
        insert(documentId, 2, "yeni yanıt", "29 days");
        insert(documentId, 3, null, "40 days");   // zaman aşımı: ham çıktı zaten yok

        assertThat(retention.purge()).isEqualTo(1);
        assertThat(retention.purge()).as("ikinci çalıştırma").isZero();

        assertThat(jdbc.sql("""
                        SELECT attempt_no || '|' || coalesce(raw_output, '-') || '|' || model || '|'
                               || (raw_output_purged_at IS NOT NULL)
                        FROM extraction_runs WHERE document_id = :id ORDER BY attempt_no
                        """).param("id", documentId).query(String.class).list())
                .containsExactly("1|-|qwen2.5:7b-instruct|true", "2|yeni yanıt|qwen2.5:7b-instruct|false",
                        "3|-|qwen2.5:7b-instruct|false");
    }

    private void insert(UUID documentId, int attempt, String raw, String age) {
        jdbc.sql("""
                        INSERT INTO extraction_runs (document_id, attempt_no, model, prompt_version, raw_output,
                                                     duration_ms, created_at)
                        VALUES (:id, :attempt, 'qwen2.5:7b-instruct', 'v3', :raw, 1000,
                                now() - CAST(:age AS interval))
                        """)
                .param("id", documentId).param("attempt", attempt).param("raw", raw).param("age", age).update();
    }
}
