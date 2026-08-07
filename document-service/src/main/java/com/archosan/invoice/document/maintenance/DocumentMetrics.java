package com.archosan.invoice.document.maintenance;

import com.archosan.invoice.document.DocumentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * document-service'in iş metrikleri (B-48, NFR-06):
 * <ul>
 *   <li>{@code invoice.documents.stuck} {status}: takılı kayıt dedektörünün son sayımı (dakikada bir).</li>
 *   <li>{@code invoice.dead.letters.open} {kind}: yöneticinin bakmadığı park kayıtları ({@code DLQ}, {@code LATE_EVENT};
 *       okumada sayılır).</li>
 * </ul>
 * Kuyruk ve DLQ derinliği broker'ın kendi metriğidir (rabbitmq_prometheus); burada yalnız broker'ın bilmedikleri.
 */
@Component
class DocumentMetrics implements MeterBinder {

    private static final String[] DEAD_LETTER_KINDS = {"DLQ", "LATE_EVENT"};

    private final StuckDocumentDetector detector;
    private final JdbcClient jdbc;

    DocumentMetrics(StuckDocumentDetector detector, JdbcClient jdbc) {
        this.detector = detector;
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (DocumentStatus status : detector.watchedStatuses()) {
            Gauge.builder("invoice.documents.stuck", detector, d -> d.lastCount(status))
                    .description("Eşikten uzun süredir başka servisi bekleyen kayıtlar")
                    .tag("status", status.name())
                    .register(registry);
        }
        for (String kind : DEAD_LETTER_KINDS) {
            Gauge.builder("invoice.dead.letters.open", this, m -> m.openDeadLetters(kind))
                    .description("Açık (OPEN) park kayıtları")
                    .tag("kind", kind)
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double openDeadLetters(String kind) {
        return jdbc.sql("SELECT count(*) FROM dead_letters WHERE status = 'OPEN' AND kind = :kind")
                .param("kind", kind).query(Long.class).single();
    }
}
