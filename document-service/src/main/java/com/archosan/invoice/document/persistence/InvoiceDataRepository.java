package com.archosan.invoice.document.persistence;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.RuleResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code invoice_data}: faturanın resmi verisi; sonraki komutlar buradan üretilir. */
@Repository
public class InvoiceDataRepository {

    public enum Source {
        LLM,
        EXPERT_CORRECTION
    }

    private final JdbcClient jdbc;
    private final InvoiceMessageConverter json;

    public InvoiceDataRepository(JdbcClient jdbc, InvoiceMessageConverter json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(UUID documentId, InvoiceFields fields, List<RuleResult> ruleResults, Source source) {
        jdbc.sql("""
                        INSERT INTO invoice_data (document_id, fields, rule_results, source)
                        VALUES (:id, CAST(:fields AS jsonb), CAST(:rules AS jsonb), :source)
                        ON CONFLICT (document_id) DO UPDATE
                        SET fields = EXCLUDED.fields, rule_results = EXCLUDED.rule_results,
                            source = EXCLUDED.source, updated_at = now()
                        """)
                .param("id", documentId)
                .param("fields", json.writeJson(fields))
                .param("rules", json.writeJson(ruleResults))
                .param("source", source.name())
                .update();
    }

    public Optional<InvoiceFields> findFields(UUID documentId) {
        return jdbc.sql("SELECT fields::text FROM invoice_data WHERE document_id = :id")
                .param("id", documentId)
                .query(String.class)
                .optional()
                .map(fields -> json.readJson(fields, InvoiceFields.class));
    }
}
