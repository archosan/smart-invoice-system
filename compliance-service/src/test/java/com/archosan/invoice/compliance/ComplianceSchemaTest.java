package com.archosan.invoice.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ortak migration'lar, ardından sözleşme (B-45) ve kontrol (B-46) şeması compliance_user olarak. */
class ComplianceSchemaTest extends ComplianceIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void appliesCommonMigrationsThenContractSchemaAsServiceUser() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list()).containsExactly("0.1", "0.2", "1", "2");
        assertThat(jdbc.sql("""
                        SELECT DISTINCT tableowner FROM pg_tables
                        WHERE schemaname = 'public'
                          AND tablename IN ('outbox', 'inbox', 'contracts', 'contract_chunks', 'compliance_checks')
                        """).query(String.class).list()).containsExactly("compliance_user");
        assertThat(jdbc.sql("""
                        SELECT format_type(atttypid, atttypmod) FROM pg_attribute
                        WHERE attrelid = 'contract_chunks'::regclass AND attname = 'embedding'
                        """).query(String.class).single()).isEqualTo("vector(1024)");
    }

    @Test
    void rejectsInvalidContractRows() {
        assertThatThrownBy(() -> insert("123", "2026-01-01", "2026-12-31", "READY", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("1234567890", "2026-12-31", "2026-01-01", "READY", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("1234567890", "2026-01-01", "2026-12-31", "FAILED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("1234567890", "2026-01-01", "2026-12-31", "READY", "neden"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(String vkn, String from, String to, String status, String reason) {
        jdbc.sql("""
                        INSERT INTO contracts (id, supplier_vkn, valid_from, valid_to, storage_uri, file_sha256, status,
                                               failure_reason, uploaded_by)
                        VALUES (:id, :vkn, CAST(:from AS date), CAST(:to AS date), 'file:///x.pdf', :sha, :status,
                                :reason, 'expert')
                        """)
                .param("id", UUID.randomUUID()).param("vkn", vkn).param("from", from).param("to", to)
                .param("sha", "a".repeat(64)).param("status", status).param("reason", reason).update();
    }
}
