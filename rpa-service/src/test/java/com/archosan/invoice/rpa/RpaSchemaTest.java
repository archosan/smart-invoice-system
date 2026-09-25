package com.archosan.invoice.rpa;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RpaSchemaTest extends RpaIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void appliesCommonMigrationsThenServiceSchemaAsServiceUser() {
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list()).containsExactly("0.1", "0.2", "1", "2", "3");
        assertThat(jdbc.sql("""
                        SELECT DISTINCT tableowner FROM pg_tables
                        WHERE schemaname = 'public'
                          AND tablename IN ('portal_submissions', 'rpa_attempts', 'outbox', 'inbox')
                        """).query(String.class).list()).containsExactly("rpa_user");
    }

    @Test
    void refNoIsPresentExactlyWhenNotInProgress() {
        assertThatThrownBy(() -> insert("IN_PROGRESS", "1000")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("SUBMITTED", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("UNKNOWN", "1000")).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(String status, String refNo) {
        jdbc.sql("""
                        INSERT INTO portal_submissions (document_id, supplier_vkn, invoice_no, status, portal_ref_no)
                        VALUES (:id, '1234567890', 'X', :status, :refNo)
                        """)
                .param("id", UUID.randomUUID()).param("status", status).param("refNo", refNo).update();
    }
}
