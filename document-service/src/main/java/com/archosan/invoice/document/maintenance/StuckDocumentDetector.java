package com.archosan.invoice.document.maintenance;

import com.archosan.invoice.document.DocumentProperties;
import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.messaging.support.BackgroundLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Takılı kayıt dedektörü (B-15): başka bir servisi bekleyen durumlarda ({@code RECEIVED}, {@code VALIDATED},
 * {@code QUEUED_FOR_RPA}) eşikten uzun kalan kayıtları sayar. İnsan bekleyen durumlar takılı sayılmaz. v1'de alarm
 * = her turda tek {@code ERROR} satırı (durum başına sayı ve en eski kayıtlar). B-48'den beri son sayım
 * {@code invoice.documents.stuck} göstergesidir ({@link DocumentMetrics}); alarm kuralı onu izler. Otomatik yeniden
 * yayın yok (A3 kapandı: ölçülen çalışmalarda takılı kayıt hiç oluşmadı; takılı kayıt çoğunlukla dış bağımlılığın
 * kapalı olduğunu gösterir, yeniden yayın kuyruğu şişirirdi).
 */
@Component
public class StuckDocumentDetector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StuckDocumentDetector.class);

    /** @param oldest en eski kayıtlar, en fazla {@code sampleSize} */
    public record Stuck(long count, List<UUID> oldest) {
    }

    private final JdbcClient jdbc;
    private final DocumentProperties.StuckDetection properties;
    private final Map<DocumentStatus, Duration> thresholds;
    private final BackgroundLoop loop;
    private volatile Map<DocumentStatus, Long> lastCounts = Map.of();

    public StuckDocumentDetector(JdbcClient jdbc, DocumentProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties.stuckDetection();
        Map<DocumentStatus, Duration> thresholds = new EnumMap<>(DocumentStatus.class);
        thresholds.put(DocumentStatus.RECEIVED, this.properties.received());
        thresholds.put(DocumentStatus.VALIDATED, this.properties.validated());
        thresholds.put(DocumentStatus.QUEUED_FOR_RPA, this.properties.queuedForRpa());
        this.thresholds = Map.copyOf(thresholds);
        this.loop = new BackgroundLoop("stuck-document-detector", this.properties.interval(), Duration.ofSeconds(5),
                () -> {
                    detect();
                    return false;
                });
    }

    /** Takılı kayıtları bulur ve varsa loglar; yalnızca takılı kaydı olan durumlar döner. */
    public Map<DocumentStatus, Stuck> detect() {
        Map<DocumentStatus, Stuck> stuck = new LinkedHashMap<>();
        for (DocumentStatus status : List.of(DocumentStatus.RECEIVED, DocumentStatus.VALIDATED,
                DocumentStatus.QUEUED_FOR_RPA)) {
            long seconds = thresholds.get(status).toSeconds();
            long count = jdbc.sql("""
                            SELECT count(*) FROM documents
                            WHERE status = :status
                              AND updated_at < now() - make_interval(secs => CAST(:seconds AS double precision))
                            """)
                    .param("status", status.name()).param("seconds", seconds)
                    .query(Long.class).single();
            if (count > 0) {
                List<UUID> oldest = jdbc.sql("""
                                SELECT id FROM documents
                                WHERE status = :status
                                  AND updated_at < now() - make_interval(secs => CAST(:seconds AS double precision))
                                ORDER BY updated_at
                                LIMIT :limit
                                """)
                        .param("status", status.name()).param("seconds", seconds)
                        .param("limit", properties.sampleSize())
                        .query(UUID.class).list();
                stuck.put(status, new Stuck(count, oldest));
            }
        }
        Map<DocumentStatus, Long> counts = new EnumMap<>(DocumentStatus.class);
        thresholds.keySet().forEach(status -> counts.put(status, stuck.containsKey(status)
                ? stuck.get(status).count() : 0L));
        lastCounts = Map.copyOf(counts);
        if (!stuck.isEmpty()) {
            log.error("Takılı kayıtlar: {}", stuck.entrySet().stream()
                    .map(e -> "%s=%d (eşik %s, en eskiler: %s)".formatted(e.getKey(), e.getValue().count(),
                            thresholds.get(e.getKey()), e.getValue().oldest()))
                    .collect(Collectors.joining("; ")));
        }
        return stuck;
    }

    /** Son turdaki takılı kayıt sayısı (B-48 göstergesi); henüz tur yoksa 0. */
    public long lastCount(DocumentStatus status) {
        return lastCounts.getOrDefault(status, 0L);
    }

    /** Takılı sayılabilen durumlar (gösterge etiketleri). */
    public Set<DocumentStatus> watchedStatuses() {
        return thresholds.keySet();
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
