package com.archosan.invoice.extraction.runs;

import com.archosan.invoice.extraction.ExtractionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Ham LLM çıktısının saklama süresi (B-47, FR-E6): gece, {@code invoice.extraction.retention.raw-output}'tan
 * (varsayılan 90 gün) eski denemelerin ham yanıtı silinir; deneme satırı (model, prompt sürümü, süre, hata) kalır.
 * Tek bir koşullu {@code UPDATE}'tir: birden çok örnek aynı anda çalıştırsa da sonuç aynıdır.
 */
@Component
public class RawOutputRetention {

    private static final Logger log = LoggerFactory.getLogger(RawOutputRetention.class);

    private final ExtractionRunRepository runs;
    private final ExtractionProperties.Retention config;
    private final Clock clock;

    @Autowired
    public RawOutputRetention(ExtractionRunRepository runs, ExtractionProperties properties) {
        this(runs, properties, Clock.systemUTC());
    }

    RawOutputRetention(ExtractionRunRepository runs, ExtractionProperties properties, Clock clock) {
        this.runs = runs;
        this.config = properties.retention();
        this.clock = clock;
    }

    @Scheduled(cron = "${invoice.extraction.retention.cron:0 30 3 * * *}")
    void scheduledPurge() {
        purge();
    }

    /** @return ham çıktısı silinen deneme sayısı */
    public int purge() {
        OffsetDateTime before = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)).minus(config.rawOutput());
        int purged = runs.purgeRawOutputBefore(before);
        if (purged > 0) {
            log.info("Ham LLM çıktısı saklama süresi doldu, silindi: {} deneme ({}'den eski)", purged, before);
        }
        return purged;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingConfiguration {
    }
}
