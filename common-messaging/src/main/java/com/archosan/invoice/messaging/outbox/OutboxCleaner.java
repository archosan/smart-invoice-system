package com.archosan.invoice.messaging.outbox;

import com.archosan.invoice.messaging.support.BackgroundLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

/**
 * Yayınlanmış ve saklama süresi dolmuş outbox satırlarını periyodik olarak, parça parça siler (A7). Yayınlanmamış
 * satıra hiç dokunmaz; relay yalnız onları kilitlediği için ikisi çakışmaz. Birden fazla servis örneği aynı anda
 * çalışsa da zararı yoktur.
 * <p>
 * Bekleme odası aynı kimlikli satırı {@link OutboxWriter#republish} ile yeniden yazabilir ({@code published_at = NULL}).
 * Koşul dış {@code DELETE}'te de tekrarlanır: satır alt sorgudan sonra yeniden yazılmışsa Postgres koşulu satırın
 * yeni hâliyle yeniden değerlendirir ve satır silinmez. Satır önce silinmişse {@code republish} onu yeniden ekler.
 */
public final class OutboxCleaner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);

    private final JdbcClient jdbc;
    private final OutboxProperties properties;
    private final BackgroundLoop loop;

    public OutboxCleaner(JdbcClient jdbc, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.loop = new BackgroundLoop("outbox-cleaner", properties.cleanupInterval(), Duration.ofSeconds(30), () -> {
            cleanUp();
            return false;
        });
    }

    /** Süresi geçmiş tüm yayınlanmış satırları siler. Döngü bunu çağırır; testler doğrudan çağırabilir. */
    public int cleanUp() {
        int total = 0;
        int deleted;
        do {
            deleted = deletePublishedOlderThan(properties.retention(), properties.cleanupBatchSize());
            total += deleted;
        } while (deleted == properties.cleanupBatchSize());
        if (total > 0) {
            log.info("Outbox temizliği: {} yayınlanmış satır silindi (saklama {})", total, properties.retention());
        }
        return total;
    }

    private int deletePublishedOlderThan(Duration retention, int limit) {
        return jdbc.sql("""
                        DELETE FROM outbox
                        WHERE published_at < now() - make_interval(secs => CAST(:seconds AS double precision))
                          AND id IN (
                            SELECT id FROM outbox
                            WHERE published_at < now() - make_interval(secs => CAST(:seconds AS double precision))
                            LIMIT :limit)
                        """)
                .param("seconds", retention.toSeconds())
                .param("limit", limit)
                .update();
    }

    @Override
    public void start() {
        loop.start();
    }

    @Override
    public void stop() {
        loop.stop();
    }

    @Override
    public boolean isRunning() {
        return loop.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return properties.cleanup().autoStartup();
    }
}
