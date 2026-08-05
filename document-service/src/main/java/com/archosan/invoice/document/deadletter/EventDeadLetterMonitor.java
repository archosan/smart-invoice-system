package com.archosan.invoice.document.deadletter;

import com.archosan.invoice.document.DocumentProperties;
import com.archosan.invoice.messaging.support.BackgroundLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * document-service'in kendi olay DLQ'larını izler (FR-D10). Bu DLQ'lar kaydı güncelleyemez ve tüketilmez; mesajlar
 * hata düzeltilince yeniden yayınlanabilsin diye kuyrukta kalır. Dolu DLQ her kontrolde {@code ERROR} loglanır
 * (v1'de alarm = log); Micrometer metriği B-48'de.
 */
@Component
public class EventDeadLetterMonitor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventDeadLetterMonitor.class);

    private final AmqpAdmin amqpAdmin;
    private final BackgroundLoop loop;

    public EventDeadLetterMonitor(AmqpAdmin amqpAdmin, DocumentProperties properties) {
        this.amqpAdmin = amqpAdmin;
        this.loop = new BackgroundLoop("event-dlq-monitor", properties.eventDlqCheckInterval(), Duration.ofSeconds(5),
                () -> {
                    check();
                    return false;
                });
    }

    /**
     * Derinlikleri pasif {@code queue.declare} ile okur, dolu olanları loglar.
     *
     * @return kuyruk → mesaj sayısı (okunamayan kuyruk -1)
     */
    public Map<String, Long> check() {
        Map<String, Long> depths = new LinkedHashMap<>();
        for (String queue : DeadLetterQueues.EVENT_DLQS) {
            QueueInformation info = amqpAdmin.getQueueInfo(queue);
            long depth = info == null ? -1 : info.getMessageCount();
            depths.put(queue, depth);
            if (depth > 0) {
                log.error("Olay DLQ'sunda {} mesaj var: kuyruk={}. Kayıtlar güncellenemedi; hata düzeltilince "
                        + "mesajlar yeniden yayınlanmalı (FR-D10)", depth, queue);
            } else if (depth < 0) {
                log.error("Olay DLQ'su okunamadı: kuyruk={}", queue);
            }
        }
        return depths;
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
}
