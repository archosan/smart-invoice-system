package com.archosan.invoice.extraction;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionSchemaTest extends ExtractionIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void appliesCommonMigrationsThenServiceSchemaAsServiceUser() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list()).containsExactly("0.1", "0.2", "1", "2");
        assertThat(jdbc.sql("""
                        SELECT DISTINCT tableowner FROM pg_tables
                        WHERE schemaname = 'public' AND tablename IN ('extraction_runs', 'outbox', 'inbox')
                        """).query(String.class).list()).containsExactly("extraction_user");
    }

    @Test
    void attemptNumberIsUniquePerDocument() {
        UUID documentId = UUID.randomUUID();
        insertRun(documentId, 1);

        assertThatThrownBy(() -> insertRun(documentId, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRun(UUID documentId, int attempt) {
        jdbc.sql("""
                        INSERT INTO extraction_runs (document_id, attempt_no, model, prompt_version)
                        VALUES (:id, :attempt, 'm', 'v1')
                        """).param("id", documentId).param("attempt", attempt).update();
    }
}
