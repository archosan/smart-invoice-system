package com.archosan.invoice.document.query;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.RuleResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Okuma tarafı (FR-D5): detay ve filtreli, sayfalı liste. Yazma yolundan ayrı tutulur. */
@Repository
public class DocumentQueries {

    private final JdbcClient jdbc;
    private final InvoiceMessageConverter json;

    public DocumentQueries(JdbcClient jdbc, InvoiceMessageConverter json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<DocumentDetail> findDetail(UUID id) {
        return jdbc.sql("""
                        SELECT d.id, d.status, d.version, d.file_sha256, d.supplier_vkn, d.invoice_no, d.invoice_date,
                               d.grand_total, d.currency, d.confidence_score, d.portal_ref_no, d.created_at,
                               d.updated_at, i.fields::text AS fields, i.rule_results::text AS rule_results,
                               c.result AS compliance_result, c.findings::text AS compliance_findings
                        FROM documents d
                        LEFT JOIN invoice_data i ON i.document_id = d.id
                        LEFT JOIN compliance_results c ON c.document_id = d.id
                        WHERE d.id = :id
                        """)
                .param("id", id)
                .query(this::mapDetail)
                .optional()
                .map(this::withDuplicates);
    }

    /** Mükerrer şüphesindeki kayıtta eşleşen kayıtlar (B-41, US-03); uzman karar verirken görür. Diğerlerinde boş. */
    private DocumentDetail withDuplicates(DocumentDetail d) {
        if (d.status() != DocumentStatus.DUPLICATE_SUSPECTED) {
            return d;
        }
        List<UUID> duplicateOf = jdbc.sql("""
                        SELECT o.id FROM documents o JOIN documents d ON d.id = :id
                        WHERE btrim(o.supplier_vkn) = btrim(d.supplier_vkn)
                          AND upper(btrim(o.invoice_no)) = upper(btrim(d.invoice_no))
                          AND o.id <> d.id AND o.status <> 'REJECTED'
                        ORDER BY o.created_at, o.id
                        """)
                .param("id", d.id())
                .query(UUID.class)
                .list();
        return new DocumentDetail(d.id(), d.status(), d.version(), d.fileSha256(), d.supplierVkn(), d.invoiceNo(),
                d.invoiceDate(), d.grandTotal(), d.currency(), d.confidenceScore(), d.portalRefNo(), d.createdAt(),
                d.updatedAt(), d.fields(), d.ruleResults(), d.compliance(), duplicateOf);
    }

    /**
     * Kaydın bütün durum geçişleri, eskiden yeniye (B-42, FR-D6, US-12); kayıt yoksa boş. Sıra {@code id}'dir: aynı
     * transaction'daki ardışık geçişlerin {@code created_at}'i eşit olabilir.
     */
    public List<HistoryEntry> history(UUID id) {
        return jdbc.sql("""
                        SELECT from_status, to_status, trigger_event, actor, reason, message_id, created_at
                        FROM status_transitions WHERE document_id = :id ORDER BY id
                        """)
                .param("id", id)
                .query((rs, n) -> new HistoryEntry(
                        rs.getString("from_status") == null ? null : DocumentStatus.valueOf(rs.getString("from_status")),
                        DocumentStatus.valueOf(rs.getString("to_status")),
                        rs.getString("trigger_event"),
                        rs.getString("actor"),
                        rs.getString("reason"),
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM documents WHERE id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    /** {@code created_at DESC, id DESC} sırasıyla bir sayfa. */
    public List<DocumentSummary> search(DocumentFilter filter, int page, int size) {
        Where where = where(filter);
        JdbcClient.StatementSpec spec = jdbc.sql("""
                        SELECT id, status, supplier_vkn, invoice_no, invoice_date, grand_total, currency, created_at,
                               updated_at
                        FROM documents
                        """ + where.sql() + """
                         ORDER BY created_at DESC, id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("limit", size)
                .param("offset", (long) page * size);
        where.params().forEach(spec::param);
        return spec.query((rs, n) -> new DocumentSummary(
                        rs.getObject("id", UUID.class),
                        DocumentStatus.valueOf(rs.getString("status")),
                        rs.getString("supplier_vkn"),
                        rs.getString("invoice_no"),
                        rs.getObject("invoice_date", LocalDate.class),
                        rs.getBigDecimal("grand_total"),
                        rs.getString("currency"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }

    public long count(DocumentFilter filter) {
        Where where = where(filter);
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT count(*) FROM documents " + where.sql());
        where.params().forEach(spec::param);
        return spec.query(Long.class).single();
    }

    private record Where(String sql, Map<String, Object> params) {
    }

    private static Where where(DocumentFilter filter) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (filter.status() != null) {
            conditions.add("status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.supplierVkn() != null) {
            conditions.add("supplier_vkn = :vkn");
            params.put("vkn", filter.supplierVkn());
        }
        if (filter.from() != null) {
            conditions.add("invoice_date >= :from");
            params.put("from", filter.from());
        }
        if (filter.to() != null) {
            conditions.add("invoice_date <= :to");
            params.put("to", filter.to());
        }
        return new Where(conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions), params);
    }

    private DocumentDetail mapDetail(ResultSet rs, int rowNum) throws SQLException {
        String fields = rs.getString("fields");
        String ruleResults = rs.getString("rule_results");
        String complianceResult = rs.getString("compliance_result");
        return new DocumentDetail(
                rs.getObject("id", UUID.class),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getString("file_sha256"),
                rs.getString("supplier_vkn"),
                rs.getString("invoice_no"),
                rs.getObject("invoice_date", LocalDate.class),
                rs.getBigDecimal("grand_total"),
                rs.getString("currency"),
                rs.getBigDecimal("confidence_score"),
                rs.getString("portal_ref_no"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                fields == null ? null : json.readJson(fields, InvoiceFields.class),
                ruleResults == null ? null : List.of(json.readJson(ruleResults, RuleResult[].class)),
                complianceResult == null ? null : new DocumentDetail.Compliance(
                        ComplianceCompleted.Result.valueOf(complianceResult),
                        List.of(json.readJson(rs.getString("compliance_findings"),
                                ComplianceCompleted.Finding[].class))),
                List.of());
    }
}
