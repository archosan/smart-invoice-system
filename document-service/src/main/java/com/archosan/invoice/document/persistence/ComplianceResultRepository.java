package com.archosan.invoice.document.persistence;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** {@code compliance_results}: onaycı ekranının kaynağı (US-06). */
@Repository
public class ComplianceResultRepository {

    private final JdbcClient jdbc;
    private final InvoiceMessageConverter json;

    public ComplianceResultRepository(JdbcClient jdbc, InvoiceMessageConverter json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(UUID documentId, ComplianceCompleted.Result result, List<ComplianceCompleted.Finding> findings) {
        jdbc.sql("""
                        INSERT INTO compliance_results (document_id, result, findings)
                        VALUES (:id, :result, CAST(:findings AS jsonb))
                        ON CONFLICT (document_id) DO UPDATE
                        SET result = EXCLUDED.result, findings = EXCLUDED.findings, created_at = now()
                        """)
                .param("id", documentId)
                .param("result", result.name())
                .param("findings", json.writeJson(findings))
                .update();
    }
}
