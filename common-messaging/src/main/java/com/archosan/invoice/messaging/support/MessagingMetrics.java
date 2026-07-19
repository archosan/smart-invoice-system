package com.archosan.invoice.messaging.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Ortak mesajlaşma metrikleri (B-48, NFR-06). Kayıt defteri yoksa (birim testler) hiçbir şey yapmaz; davranışı
 * etkilemez. Prometheus adları: {@code invoice_messages_processed_seconds_*},
 * {@code invoice_dead_letters_received_total}, {@code invoice_outbox_relay_total}.
 *
 * <ul>
 *   <li>{@code invoice.messages.processed} (timer): kuyruk, tip, sonuç ({@code ack}, {@code duplicate},
 *       {@code deferred}, {@code requeue}, {@code dead_letter}). {@code requeue} sayısı retry metriğidir.</li>
 *   <li>{@code invoice.dead.letters.received} (counter): DLQ dinleyicisinin aldığı mesajlar, kuyruk ve tip.</li>
 *   <li>{@code invoice.outbox.relay} (counter): relay yayınları, tip ve sonuç ({@code published}, {@code failed}).</li>
 * </ul>
 */
public final class MessagingMetrics {

    public static final MessagingMetrics NONE = new MessagingMetrics(null);

    static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public MessagingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void processed(String queue, String type, String outcome, long nanos) {
        if (registry == null) {
            return;
        }
        Timer.builder("invoice.messages.processed")
                .description("Dinleyicinin bir mesajı işleme süresi, sonuca göre")
                .tags("queue", queue, "type", orUnknown(type), "outcome", outcome.toLowerCase(Locale.ROOT))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void deadLetterReceived(String queue, String type) {
        if (registry == null) {
            return;
        }
        Counter.builder("invoice.dead.letters.received")
                .description("DLQ dinleyicisinin aldığı mesajlar")
                .tags("queue", queue, "type", orUnknown(type))
                .register(registry)
                .increment();
    }

    public void outboxRelayed(String type, boolean published) {
        if (registry == null) {
            return;
        }
        Counter.builder("invoice.outbox.relay")
                .description("Relay'in yayın denemeleri")
                .tags("type", orUnknown(type), "result", published ? "published" : "failed")
                .register(registry)
                .increment();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
