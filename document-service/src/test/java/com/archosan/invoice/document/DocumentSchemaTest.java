package com.archosan.invoice.document;

import com.archosan.invoice.testsupport.InfrastructureContainers;
import com.archosan.invoice.testsupport.ServiceDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V1 migration'ı document_user olarak uygulanır; kısıtlar ve başlangıç ayarları beklendiği gibidir (B-09). */
class DocumentSchemaTest extends DocumentIntegrationTest {

    private static final String SHA = "a".repeat(64);

    @Autowired
    private JdbcClient jdbc;

    @Test
    void appliesCommonMigrationsBeforeServiceSchema() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list())
                .containsExactly("0.1", "0.2", "1", "2", "3", "4", "5");
    }

    @Test
    void tablesAreOwnedByServiceUser() {
        assertThat(jdbc.sql("""
                        SELECT DISTINCT tableowner FROM pg_tables
                        WHERE schemaname = 'public' AND tablename IN ('documents', 'invoice_data', 'compliance_results',
                            'status_transitions', 'dead_letters', 'settings', 'settings_changes', 'outbox', 'inbox')
                        """)
                .query(String.class).list())
                .containsExactly("document_user");
    }

    @Test
    void seedsPlaceholderSettings() {
        assertThat(jdbc.sql("SELECT value FROM settings WHERE key = 'confidence_threshold'").query(String.class).single())
                .isEqualTo("0.80");
        assertThat(jdbc.sql("SELECT value FROM settings WHERE key = 'approval_amount_threshold'")
                .query(String.class).single())
                .isEqualTo("100000.00");
    }

    @Test
    void rejectsSecondDocumentWithSameHash() {
        insertDocument(UUID.randomUUID(), SHA, "RECEIVED");

        assertThatThrownBy(() -> insertDocument(UUID.randomUUID(), SHA, "RECEIVED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCDEF" + "0123456789012345678901234567890123456789012345678901234567", "kısa", ""})
    void rejectsMalformedHash(String hash) {
        assertThatThrownBy(() -> insertDocument(UUID.randomUUID(), hash, "RECEIVED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> insertDocument(UUID.randomUUID(), SHA, "EXTRACTING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnknownTransitionStatus() {
        UUID id = UUID.randomUUID();
        insertDocument(id, SHA, "RECEIVED");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO status_transitions (document_id, from_status, to_status, trigger_event, actor)
                        VALUES (:id, 'RECEIVED', 'DONE', 'test', 'test')
                        """).param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** B-42, NFR-05: servis kullanıcısı geçmişe yalnız ekleyebilir; yetki geri alındı. */
    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE status_transitions SET reason = 'değişti' WHERE document_id = :id",
            "DELETE FROM status_transitions WHERE document_id = :id",
            "TRUNCATE status_transitions CASCADE"})
    void serviceUserCannotChangeHistory(String sql) {
        UUID id = insertDocumentWithTransition();

        assertThatThrownBy(() -> jdbc.sql(sql.replace(":id", "'" + id + "'")).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table status_transitions");
        assertThat(jdbc.sql("SELECT count(*) FROM status_transitions WHERE document_id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(1);
    }

    /** İkinci katman: yetkiyi dikkate almayan superuser bile tetikleyiciye takılır (yetki yanlışlıkla geri verilse). */
    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE status_transitions SET reason = 'değişti' WHERE document_id = '%s'",
            "DELETE FROM status_transitions WHERE document_id = '%s'",
            "TRUNCATE status_transitions CASCADE"})
    void triggerRejectsChangesEvenWithPrivilege(String sql) throws SQLException {
        UUID id = insertDocumentWithTransition();

        try (Connection connection = InfrastructureContainers.superuserConnection(ServiceDatabase.DOCUMENT);
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql.formatted(id)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("status_transitions yalnızca eklenir");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM status_transitions WHERE document_id = :id").param("id", id)
                .query(Long.class).single()).isEqualTo(1);
    }

    private UUID insertDocumentWithTransition() {
        UUID id = UUID.randomUUID();
        insertDocument(id, SHA, "RECEIVED");
        jdbc.sql("""
                        INSERT INTO status_transitions (document_id, from_status, to_status, trigger_event, actor)
                        VALUES (:id, NULL, 'RECEIVED', 'UPLOAD', 'api')
                        """).param("id", id).update();
        return id;
    }

    @Test
    void storesUnvalidatedExtractedValuesWithoutRoundingOrFailing() {
        UUID id = UUID.randomUUID();
        insertDocument(id, SHA, "NEEDS_REVIEW");

        jdbc.sql("""
                        UPDATE documents SET supplier_vkn = '12345678901', currency = 'TL', grand_total = 100.005
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();

        assertThat(jdbc.sql("SELECT grand_total FROM documents WHERE id = :id").param("id", id)
                .query(BigDecimal.class).single())
                .isEqualByComparingTo("100.005");
        assertThat(jdbc.sql("SELECT supplier_vkn FROM documents WHERE id = :id").param("id", id)
                .query(String.class).single())
                .isEqualTo("12345678901");
    }

    @Test
    void rejectsUnknownInvoiceDataSource() {
        UUID id = UUID.randomUUID();
        insertDocument(id, SHA, "EXTRACTED");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO invoice_data (document_id, fields, rule_results, source)
                        VALUES (:id, '{}', '[]', 'GUESS')
                        """).param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertDocument(UUID id, String sha, String status) {
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status)
                        VALUES (:id, :sha, :uri, :status)
                        """)
                .param("id", id)
                .param("sha", sha)
                .param("uri", "file:///data/documents/" + sha + ".pdf")
                .param("status", status)
                .update();
    }
}
