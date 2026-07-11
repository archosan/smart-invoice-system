package com.archosan.invoice.messaging.inbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;

/**
 * İşlenmiş mesaj kaydı. {@code consumer} kuyruk adıdır; dinleyici bunu kendisi verir, servis kodu inbox'ı
 * doğrudan çağırmaz ({@link com.archosan.invoice.messaging.consumer.InvoiceMessageListener}).
 */
public final class Inbox {

    private final JdbcClient jdbc;

    public Inbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ucuz ön kontrol; uzun işlerde transaction dışında çağrılır. Kesin karar {@link #tryInsert}'tedir. */
    public boolean exists(UUID messageId, String consumer) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM inbox WHERE message_id = :messageId AND consumer = :consumer)")
                .param("messageId", messageId)
                .param("consumer", consumer)
                .query(Boolean.class)
                .single();
    }

    /**
     * Mesajı işlenmiş olarak kaydeder; iş verisiyle aynı transaction'da çağrılmalıdır.
     *
     * <p>Aynı mesaj başka bir transaction'da henüz commit olmadan kaydedildiyse bu çağrı onun sonucunu bekler: o
     * commit olursa {@code false}, geri alınırsa {@code true} döner.
     *
     * @return kayıt yeni eklendiyse {@code true}; mesaj daha önce işlendiyse {@code false}
     */
    public boolean tryInsert(UUID messageId, String consumer) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Inbox'a yalnızca iş transaction'ı içinde yazılır");
        }
        return jdbc.sql("""
                        INSERT INTO inbox (message_id, consumer) VALUES (:messageId, :consumer)
                        ON CONFLICT (message_id, consumer) DO NOTHING
                        """)
                .param("messageId", messageId)
                .param("consumer", consumer)
                .update() == 1;
    }

    /**
     * {@code retention}'dan eski satırların en fazla {@code limit} tanesini siler. Uzun kilit tutmamak için parça
     * parça çağrılır ({@link InboxCleaner}).
     *
     * @return silinen satır sayısı
     */
    public int deleteOlderThan(Duration retention, int limit) {
        return jdbc.sql("""
                        DELETE FROM inbox WHERE ctid IN (
                            SELECT ctid FROM inbox
                            WHERE processed_at < now() - make_interval(secs => CAST(:seconds AS double precision))
                            LIMIT :limit)
                        """)
                .param("seconds", retention.toSeconds())
                .param("limit", limit)
                .update();
    }
}
