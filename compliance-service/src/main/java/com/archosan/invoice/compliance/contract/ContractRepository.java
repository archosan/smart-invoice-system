package com.archosan.invoice.compliance.contract;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code contracts} ve {@code contract_chunks} (B-45). */
@Repository
public class ContractRepository {

    public enum Status {
        INGESTING,
        READY,
        FAILED
    }

    public record Contract(UUID id, String supplierVkn, LocalDate validFrom, LocalDate validTo, String storageUri,
            String fileSha256, Status status, String failureReason, String uploadedBy, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** Filtreli aramada getirilen chunk (B-46). */
    public record RetrievedChunk(long id, String clauseNo, String content) {
    }

    /** Chunk'ın embedding'siz özeti (API). */
    public record ChunkSummary(int index, String clauseNo, String title, int part, int page, int chars) {
    }

    /** Yazılacak chunk; {@code embedding} {@code [0.1,…]} biçiminde pgvector metni. */
    public record NewChunk(int index, String clauseNo, String title, int part, String content, int page,
            String embedding) {
    }

    private static final String COLUMNS = """
            id, supplier_vkn, valid_from, valid_to, storage_uri, file_sha256, status, failure_reason, uploaded_by,
            created_at, updated_at""";

    private final JdbcClient jdbc;

    public ContractRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return kayıt açıldıysa {@code true}; aynı dosya zaten varsa {@code false} (eşzamanlı yükleme unique'te bekler)
     */
    public boolean insertIfAbsent(UUID id, String vkn, LocalDate from, LocalDate to, String storageUri,
            String sha256, String uploadedBy) {
        return jdbc.sql("""
                        INSERT INTO contracts (id, supplier_vkn, valid_from, valid_to, storage_uri, file_sha256,
                                               status, uploaded_by)
                        VALUES (:id, :vkn, :from, :to, :uri, :sha, 'INGESTING', :by)
                        ON CONFLICT (file_sha256) DO NOTHING
                        RETURNING id
                        """)
                .param("id", id).param("vkn", vkn).param("from", from).param("to", to).param("uri", storageUri)
                .param("sha", sha256).param("by", uploadedBy)
                .query(UUID.class).optional().isPresent();
    }

    public Optional<Contract> findBySha256(String sha256) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contracts WHERE file_sha256 = :sha")
                .param("sha", sha256).query(this::map).optional();
    }

    public Optional<Contract> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contracts WHERE id = :id").param("id", id).query(this::map)
                .optional();
    }

    public List<Contract> findByVkn(String vkn) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM contracts"
                        + " WHERE (CAST(:vkn AS text) IS NULL OR supplier_vkn = :vkn)"
                        + " ORDER BY supplier_vkn, valid_from DESC, created_at DESC")
                .param("vkn", vkn).query(this::map).list();
    }

    /** Aynı tedarikçide aralığı kesişen, {@code FAILED} olmayan diğer sözleşmeler (uyarı; B-46 çakışma sayar). */
    public List<Contract> findOverlapping(UUID id, String vkn, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM contracts
                        WHERE supplier_vkn = :vkn AND id <> :id AND status <> 'FAILED'
                          AND valid_from <= :to AND valid_to >= :from
                        ORDER BY valid_from
                        """)
                .param("id", id).param("vkn", vkn).param("from", from).param("to", to).query(this::map).list();
    }

    /**
     * Fatura tarihinde geçerli, indekslenmiş sözleşmeler (FR-C2 adım 1, ADR-09): 0 → {@code NO_CONTRACT}, 2+ →
     * {@code CONTRACT_CONFLICT}. Süre kontrolü burada biter, LLM'e gitmez; yarım indeksli sözleşme seçilmez.
     */
    public List<Contract> findValid(String vkn, LocalDate invoiceDate) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM contracts
                        WHERE supplier_vkn = :vkn AND :date BETWEEN valid_from AND valid_to AND status = 'READY'
                        ORDER BY valid_from
                        """)
                .param("vkn", vkn).param("date", invoiceDate).query(this::map).list();
    }

    /** Sözleşmeye filtrelenmiş tam tarama, kosinüs uzaklığına göre (FR-C2 adım 3); HNSW yok (ADR-09). */
    public List<RetrievedChunk> nearest(UUID contractId, String queryVector, int limit) {
        return jdbc.sql("""
                        SELECT id, clause_no, content FROM contract_chunks
                        WHERE contract_id = :id
                        ORDER BY embedding <=> CAST(:q AS vector), chunk_index
                        LIMIT :limit
                        """)
                .param("id", contractId).param("q", queryVector).param("limit", limit)
                .query((rs, n) -> new RetrievedChunk(rs.getLong("id"), rs.getString("clause_no"),
                        rs.getString("content")))
                .list();
    }

    /** {@code from → to}, koşullu; 1 satır güncellendiyse {@code true}. {@code FAILED}'a geçerken neden zorunlu. */
    public boolean transition(UUID id, Status from, Status to, String failureReason) {
        return jdbc.sql("""
                        UPDATE contracts SET status = :to, failure_reason = :reason, updated_at = now()
                        WHERE id = :id AND status = :from
                        """)
                .param("id", id).param("from", from.name()).param("to", to.name()).param("reason", failureReason)
                .update() == 1;
    }

    /** Önceki (yarım kalmış) denemenin chunk'larını siler ve yenilerini yazar; çağıranın transaction'ında. */
    public void replaceChunks(UUID contractId, List<NewChunk> chunks, String model) {
        jdbc.sql("DELETE FROM contract_chunks WHERE contract_id = :id").param("id", contractId).update();
        for (NewChunk c : chunks) {
            jdbc.sql("""
                            INSERT INTO contract_chunks (contract_id, chunk_index, clause_no, title, part, content,
                                                         page, embedding, embedding_model)
                            VALUES (:id, :index, :clauseNo, :title, :part, :content, :page, CAST(:embedding AS vector),
                                    :model)
                            """)
                    .param("id", contractId).param("index", c.index()).param("clauseNo", c.clauseNo())
                    .param("title", c.title()).param("part", c.part()).param("content", c.content())
                    .param("page", c.page()).param("embedding", c.embedding()).param("model", model)
                    .update();
        }
    }

    public List<ChunkSummary> chunks(UUID contractId) {
        return jdbc.sql("""
                        SELECT chunk_index, clause_no, title, part, page, length(content) AS chars
                        FROM contract_chunks WHERE contract_id = :id ORDER BY chunk_index
                        """)
                .param("id", contractId)
                .query((rs, n) -> new ChunkSummary(rs.getInt("chunk_index"), rs.getString("clause_no"),
                        rs.getString("title"), rs.getInt("part"), rs.getInt("page"), rs.getInt("chars")))
                .list();
    }

    private Contract map(ResultSet rs, int rowNum) throws SQLException {
        return new Contract(rs.getObject("id", UUID.class), rs.getString("supplier_vkn"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                rs.getString("storage_uri"), rs.getString("file_sha256"), Status.valueOf(rs.getString("status")),
                rs.getString("failure_reason"), rs.getString("uploaded_by"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
