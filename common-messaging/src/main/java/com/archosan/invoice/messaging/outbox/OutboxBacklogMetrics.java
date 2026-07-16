package com.archosan.invoice.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Outbox birikimi (B-48, NFR-06): yayınlanmamış satır sayısı ve en eskisinin yaşı (sn). Relay durursa ya da broker
 * reddederse büyür; alarm kuralı yaşa bakar ({@code infra/prometheus/alerts.yml}). Değerler her okumada kısmi indeksle
 * ({@code published_at IS NULL}) sorgulanır.
 */
public final class OutboxBacklogMetrics implements MeterBinder {

    private final JdbcClient jdbc;

    public OutboxBacklogMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("invoice.outbox.pending", this, OutboxBacklogMetrics::pending)
                .description("Yayınlanmamış outbox satırları")
                .strongReference(true)
                .register(registry);
        Gauge.builder("invoice.outbox.oldest.pending.age", this, OutboxBacklogMetrics::oldestAgeSeconds)
                .description("En eski yayınlanmamış outbox satırının yaşı")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    private double pending() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }

    private double oldestAgeSeconds() {
        return jdbc.sql("""
                        SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
                        FROM outbox WHERE published_at IS NULL
                        """).query(Double.class).single();
    }
}
