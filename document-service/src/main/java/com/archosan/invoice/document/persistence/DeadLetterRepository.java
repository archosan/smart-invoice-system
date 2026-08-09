package com.archosan.invoice.document.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code dead_letters}: DLQ park tablosu ve geç olaylar. */
@Repository
public class DeadLetterRepository {

    private final JdbcClient jdbc;

    public DeadLetterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Uygulanamayan (geç, sırasız) olayı {@code LATE_EVENT}, {@code OPEN} olarak kaydeder. */
    public UUID insertLateEvent(String sourceQueue, String messageType, UUID messageId, UUID documentId,
            String bodyJson) {
        return insert("LATE_EVENT", sourceQueue, messageType, messageId, documentId, bodyJson, null);
    }

    /**
     * DLQ'dan gelen mesajı {@code DLQ}, {@code OPEN} olarak park eder (ADR-18).
     *
     * @param sourceQueue mesajın ilk öldüğü asıl kuyruk; v2'deki yeniden işletme bunu kullanır
     */
    public UUID insertDeadLetter(String sourceQueue, String messageType, UUID messageId, UUID documentId,
            String bodyJson, String xDeathJson) {
        return insert("DLQ", sourceQueue, messageType, messageId, documentId, bodyJson, xDeathJson);
    }

    /** Yönetici listesindeki satır (B-38). */
    public record Summary(UUID id, String kind, String sourceQueue, String messageType, UUID messageId,
            UUID documentId, String status, OffsetDateTime createdAt) {
    }

    /** Tek kayıt: gövde ve {@code x-death} JSON metin olarak. */
    public record Detail(UUID id, String kind, String sourceQueue, String messageType, UUID messageId,
            UUID documentId, String status, OffsetDateTime createdAt, String body, String xDeath) {
    }

    /** Yeni kayıt önce; {@code status} boşsa hepsi. */
    public List<Summary> page(String status, int page, int size) {
        return jdbc.sql("""
                        SELECT id, kind, source_queue, message_type, message_id, document_id, status, created_at
                        FROM dead_letters
                        WHERE (CAST(:status AS text) IS NULL OR status = :status)
                        ORDER BY created_at DESC, id
                        LIMIT :size OFFSET :offset
                        """)
                .param("status", status)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(Summary.class)
                .list();
    }

    public long count(String status) {
        return jdbc.sql("SELECT count(*) FROM dead_letters WHERE (CAST(:status AS text) IS NULL OR status = :status)")
                .param("status", status)
                .query(Long.class)
                .single();
    }

    public Optional<Detail> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, kind, source_queue, message_type, message_id, document_id, status, created_at,
                               body::text AS body, x_death::text AS x_death
                        FROM dead_letters WHERE id = :id
                        """)
                .param("id", id)
                .query(Detail.class)
                .optional();
    }

    /** {@code OPEN → to}, koşullu; çağıranın transaction'ında. Satır OPEN değilse 0 döner. */
    public int close(UUID id, String to) {
        return jdbc.sql("UPDATE dead_letters SET status = :to WHERE id = :id AND status = 'OPEN'")
                .param("id", id)
                .param("to", to)
                .update();
    }

    private UUID insert(String kind, String sourceQueue, String messageType, UUID messageId, UUID documentId,
            String bodyJson, String xDeathJson) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO dead_letters
                            (id, kind, source_queue, message_type, message_id, document_id, body, x_death, status)
                        VALUES (:id, :kind, :sourceQueue, :messageType, :messageId, :documentId,
                                CAST(:body AS jsonb), CAST(:xDeath AS jsonb), 'OPEN')
                        """)
                .param("id", id)
                .param("kind", kind)
                .param("sourceQueue", sourceQueue)
                .param("messageType", messageType)
                .param("messageId", messageId)
                .param("documentId", documentId)
                .param("body", bodyJson)
                .param("xDeath", xDeathJson)
                .update();
        return id;
    }
}
