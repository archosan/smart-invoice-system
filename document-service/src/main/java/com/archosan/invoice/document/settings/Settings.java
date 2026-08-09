package com.archosan.invoice.document.settings;

import com.archosan.invoice.document.DocumentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code settings} tablosu (FR-A2), bellekte {@code invoice.document.settings-cache-ttl} (varsayılan 5 sn) tutulur.
 * Başka örnekte yapılan değişiklik en geç bu süre sonunda yansır; ayarlar API'si ({@link SettingsAdmin}) kendi
 * örneğinin önbelleğini commit'ten sonra hemen boşaltır. Tablonun tamamı tek sorguyla okunur.
 */
@Component
public class Settings {

    public static final String CONFIDENCE_THRESHOLD = "confidence_threshold";
    public static final String APPROVAL_AMOUNT_THRESHOLD = "approval_amount_threshold";

    private record Snapshot(Map<String, String> values, Instant loadedAt) {
    }

    private final JdbcClient jdbc;
    private final Duration ttl;
    private final Clock clock;
    private volatile Snapshot snapshot;

    @Autowired
    public Settings(JdbcClient jdbc, DocumentProperties properties) {
        this(jdbc, properties.settingsCacheTtl(), Clock.systemUTC());
    }

    Settings(JdbcClient jdbc, Duration ttl, Clock clock) {
        this.jdbc = jdbc;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Güven eşiği; skor bunun altındaysa kayıt {@code NEEDS_REVIEW}'a gider (ADR-13). */
    public BigDecimal confidenceThreshold() {
        return new BigDecimal(required(CONFIDENCE_THRESHOLD));
    }

    /** Tutar eşiği; genel toplam bunu aşarsa kayıt {@code PENDING_APPROVAL}'a gider (FR-D11, B-39). */
    public BigDecimal approvalAmountThreshold() {
        return new BigDecimal(required(APPROVAL_AMOUNT_THRESHOLD));
    }

    /** Bir sonraki okuma tabloya gider. Commit'ten sonra çağrılmalı; önce çağrılırsa eski değer yeniden önbelleklenir. */
    void invalidate() {
        snapshot = null;
    }

    private String required(String key) {
        String value = current().get(key);
        if (value == null) {
            throw new IllegalStateException("settings tablosunda değer yok: " + key);
        }
        return value;
    }

    private Map<String, String> current() {
        Snapshot current = snapshot;
        if (current == null || isExpired(current)) {
            synchronized (this) {
                current = snapshot;
                if (current == null || isExpired(current)) {
                    current = new Snapshot(load(), clock.instant());
                    snapshot = current;
                }
            }
        }
        return current.values();
    }

    private boolean isExpired(Snapshot current) {
        return !clock.instant().isBefore(current.loadedAt().plus(ttl));
    }

    private Map<String, String> load() {
        return jdbc.sql("SELECT key, value FROM settings")
                .query((rs, n) -> Map.entry(rs.getString("key"), rs.getString("value")))
                .list().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
