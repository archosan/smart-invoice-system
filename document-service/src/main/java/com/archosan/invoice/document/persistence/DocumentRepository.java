package com.archosan.invoice.document.persistence;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.messaging.message.InvoiceFields;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** {@code documents} ve {@code status_transitions} erişimi; açık SQL ({@link JdbcClient}). */
@Repository
public class DocumentRepository {

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Hash yeniyse {@code RECEIVED} durumunda kayıt açar. Aynı hash başka bir transaction'da henüz commit olmadan
     * eklendiyse onun sonucunu bekler (unique kısıt); eşzamanlı iki yüklemeyi bu çözer, kilit gerekmez.
     *
     * @return kayıt açıldıysa {@code true}; hash zaten varsa {@code false}
     */
    public boolean insertIfAbsent(UUID id, String fileSha256, String storageUri, String uploadedBy) {
        return jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status, uploaded_by)
                        VALUES (:id, :sha, :storageUri, 'RECEIVED', :uploadedBy)
                        ON CONFLICT (file_sha256) DO NOTHING
                        RETURNING id
                        """)
                .param("id", id)
                .param("sha", fileSha256)
                .param("storageUri", storageUri)
                .param("uploadedBy", uploadedBy)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public Optional<DocumentRef> findBySha256(String fileSha256) {
        return jdbc.sql("SELECT id, status FROM documents WHERE file_sha256 = :sha")
                .param("sha", fileSha256)
                .query((rs, n) -> new DocumentRef(rs.getObject("id", UUID.class),
                        DocumentStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    /**
     * Koşullu durum güncellemesi (idempotency katmanı 2): yalnızca kayıt {@code from} durumundaysa (ve verildiyse
     * {@code expectedVersion} sürümündeyse) uygulanır; {@code version} artar, {@code updated_at} yenilenir. Aynı
     * kayda eşzamanlı ikinci güncelleme satır kilidini bekler, sonra 0 satır alır.
     *
     * @return güncellenen satır sayısı (0 veya 1)
     */
    public int updateStatus(UUID id, DocumentStatus from, DocumentStatus to, Integer expectedVersion) {
        return jdbc.sql("""
                        UPDATE documents
                        SET status = :to, version = version + 1, updated_at = now()
                        WHERE id = :id AND status = :from
                          AND (CAST(:expectedVersion AS integer) IS NULL OR version = :expectedVersion)
                        """)
                .param("id", id)
                .param("from", from.name())
                .param("to", to.name())
                .param("expectedVersion", expectedVersion)
                .update();
    }

    /** Çıkarılan alanların arama ve liste için kopyası; resmi veri {@code invoice_data}'dadır. */
    public void updateExtractedFields(UUID id, InvoiceFields fields, BigDecimal confidenceScore) {
        jdbc.sql("""
                        UPDATE documents
                        SET supplier_vkn = :vkn, invoice_no = :invoiceNo, invoice_date = :invoiceDate,
                            grand_total = :grandTotal, currency = :currency, confidence_score = :score
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("vkn", fields == null ? null : fields.supplierVkn())
                .param("invoiceNo", fields == null ? null : fields.invoiceNo())
                .param("invoiceDate", fields == null ? null : fields.invoiceDate())
                .param("grandTotal", fields == null ? null : fields.grandTotal())
                .param("currency", fields == null ? null : fields.currency())
                .param("score", confidenceScore)
                .update();
    }

    /**
     * Uzman düzeltmesinden sonra arama kopyaları (B-40). Güven skoru LLM çıkarımına aittir, değişmez; düzeltilen
     * kaydın kaynağı {@code invoice_data.source}'tadır.
     */
    public void updateFieldCopies(UUID id, InvoiceFields fields) {
        jdbc.sql("""
                        UPDATE documents
                        SET supplier_vkn = :vkn, invoice_no = :invoiceNo, invoice_date = :invoiceDate,
                            grand_total = :grandTotal, currency = :currency
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("vkn", fields.supplierVkn())
                .param("invoiceNo", fields.invoiceNo())
                .param("invoiceDate", fields.invoiceDate())
                .param("grandTotal", fields.grandTotal())
                .param("currency", fields.currency())
                .update();
    }

    /** API eylemlerinin hata ayrımı için (404 / 409 / 412); karar yine koşullu geçiştedir. */
    public Optional<State> findState(UUID id) {
        return jdbc.sql("SELECT status, version, uploaded_by FROM documents WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new State(DocumentStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                        rs.getString("uploaded_by")))
                .optional();
    }

    /** @param uploadedBy yükleyen kullanıcı; B-43'ten önceki kayıtlarda {@code null} */
    public record State(DocumentStatus status, int version, String uploadedBy) {
    }

    /**
     * Aynı faturanın (kırpılmış VKN, kırpılmış ve büyük harfli fatura no) belgelerini sıraya sokar: kilit transaction
     * sonuna kadar tutulur, ikinci belge birincinin commit'ini bekler ve onu görür (B-41). Kilit yalnızca aynı anahtarı
     * bekletir.
     */
    public void lockInvoiceKey(String supplierVkn, String invoiceNo) {
        jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked")
                .param("key", supplierVkn.strip() + "|" + invoiceNo.strip().toUpperCase(Locale.ROOT))
                .query(Integer.class).single();
    }

    /** {@code REJECTED} dışındaki, aynı faturaya ait diğer kayıtlar, eskiden yeniye (FR-D8). */
    public List<Match> findSameInvoice(UUID id, String supplierVkn, String invoiceNo) {
        return jdbc.sql("""
                        SELECT id, status FROM documents
                        WHERE btrim(supplier_vkn) = :vkn AND upper(btrim(invoice_no)) = :invoiceNo
                          AND id <> :id AND status <> 'REJECTED'
                        ORDER BY created_at, id
                        """)
                .param("id", id)
                .param("vkn", supplierVkn.strip())
                .param("invoiceNo", invoiceNo.strip().toUpperCase(Locale.ROOT))
                .query((rs, n) -> new Match(rs.getObject("id", UUID.class),
                        DocumentStatus.valueOf(rs.getString("status"))))
                .list();
    }

    public record Match(UUID id, DocumentStatus status) {
    }

    public void updatePortalRefNo(UUID id, String portalRefNo) {
        jdbc.sql("UPDATE documents SET portal_ref_no = :ref WHERE id = :id")
                .param("id", id)
                .param("ref", portalRefNo)
                .update();
    }

    /** Bekleyen {@code PostToPortal}'ın kimliği (B-38, A4); yeni tur başlayınca değişir. */
    public void setPendingRpaCommand(UUID id, UUID commandMessageId) {
        jdbc.sql("UPDATE documents SET pending_rpa_command_id = :command WHERE id = :id")
                .param("id", id)
                .param("command", commandMessageId)
                .update();
    }

    public Optional<UUID> findPendingRpaCommand(UUID id) {
        return jdbc.sql("""
                        SELECT pending_rpa_command_id FROM documents
                        WHERE id = :id AND pending_rpa_command_id IS NOT NULL
                        """)
                .param("id", id)
                .query(UUID.class)
                .optional();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM documents WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** {@code status_transitions}'a satır ekler (append-only). */
    public void recordTransition(UUID documentId, DocumentStatus from, DocumentStatus to, String triggerEvent,
            UUID messageId, String actor, String reason) {
        jdbc.sql("""
                        INSERT INTO status_transitions
                            (document_id, from_status, to_status, trigger_event, message_id, actor, reason)
                        VALUES (:documentId, :from, :to, :trigger, :messageId, :actor, :reason)
                        """)
                .param("documentId", documentId)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("trigger", triggerEvent)
                .param("messageId", messageId)
                .param("actor", actor)
                .param("reason", reason)
                .update();
    }
}
