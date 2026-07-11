package com.archosan.invoice.messaging.inbox;

import com.archosan.invoice.messaging.support.BackgroundLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;

/**
 * Süresi geçmiş inbox satırlarını periyodik olarak, parça parça siler. Birden fazla servis örneği aynı anda çalışsa
 * da zararı yoktur.
 */
public final class InboxCleaner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InboxCleaner.class);

    private final Inbox inbox;
    private final InboxProperties properties;
    private final BackgroundLoop loop;

    public InboxCleaner(Inbox inbox, InboxProperties properties) {
        this.inbox = inbox;
        this.properties = properties;
        this.loop = new BackgroundLoop("inbox-cleaner", properties.cleanupInterval(), Duration.ofSeconds(30), () -> {
            cleanUp();
            return false;
        });
    }

    /** Süresi geçmiş tüm satırları siler. Döngü bunu çağırır; testler doğrudan çağırabilir. */
    public int cleanUp() {
        int total = 0;
        int deleted;
        do {
            deleted = inbox.deleteOlderThan(properties.retention(), properties.cleanupBatchSize());
            total += deleted;
        } while (deleted == properties.cleanupBatchSize());
        if (total > 0) {
            log.info("Inbox temizliği: {} satır silindi (saklama {})", total, properties.retention());
        }
        return total;
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
