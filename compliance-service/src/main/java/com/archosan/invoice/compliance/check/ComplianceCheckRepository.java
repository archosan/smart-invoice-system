package com.archosan.invoice.compliance.check;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.stream.Collectors;

/** {@code compliance_checks} (B-46): her kontrolün izi; yalnız eklenir. */
@Repository
class ComplianceCheckRepository {

    private final JdbcClient jdbc;
    private final InvoiceMessageConverter json;

    ComplianceCheckRepository(JdbcClient jdbc, InvoiceMessageConverter json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void save(UUID documentId, ComplianceEvaluator.Evaluation evaluation) {
        jdbc.sql("""
                        INSERT INTO compliance_checks (document_id, contract_id, result, findings, retrieved_chunk_ids,
                                                       model)
                        VALUES (:documentId, :contractId, :result, CAST(:findings AS jsonb),
                                CAST(:chunks AS bigint[]), :model)
                        """)
                .param("documentId", documentId)
                .param("contractId", evaluation.contractId())
                .param("result", evaluation.result().name())
                .param("findings", json.writeJson(evaluation.findings()))
                .param("chunks", evaluation.retrievedChunkIds().stream().map(String::valueOf)
                        .collect(Collectors.joining(",", "{", "}")))
                .param("model", evaluation.model())
                .update();
    }
}
