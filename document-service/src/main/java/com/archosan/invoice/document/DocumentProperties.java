package com.archosan.invoice.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;

/**
 * document-service ayarları ({@code invoice.document.*}).
 *
 * @param storageDir            PDF'lerin içerik adresli saklandığı dizin; extraction-service aynı volume'u salt okunur
 *                              bağlar (ADR-11)
 * @param settingsCacheTtl      {@code settings} tablosunun bellekte tutulma süresi (FR-A2)
 * @param listener              olay kuyruklarının prefetch ve eşzamanlılığı (DECISIONS.md §5: 10 · 2)
 * @param eventDlqCheckInterval olay DLQ'larının derinlik kontrolü aralığı (FR-D10)
 * @param stuckDetection        takılı kayıt dedektörü (B-15)
 * @param orphanCleanup         sahipsiz dosya temizliği (B-15)
 * @param correction            uzman düzeltmesinin doğrulama toleransları (B-40)
 */
@ConfigurationProperties("invoice.document")
public record DocumentProperties(
        @DefaultValue("/data/documents") Path storageDir,
        @DefaultValue("5s") Duration settingsCacheTtl,
        @DefaultValue Listener listener,
        @DefaultValue("1m") Duration eventDlqCheckInterval,
        @DefaultValue StuckDetection stuckDetection,
        @DefaultValue OrphanCleanup orphanCleanup,
        @DefaultValue Correction correction) {

    public record Listener(@DefaultValue("10") int prefetch, @DefaultValue("2") int concurrency) {
    }

    /**
     * Başka bir servisi bekleyen durumlar için eşikler; {@code updated_at} bu süreden eskiyse kayıt takılı sayılır.
     *
     * @param received     LLM sırası beklenir (en fazla 2 eşzamanlı çağrı), toplu yüklemede uzar
     * @param queuedForRpa portal işlemi ve servis içi retry'lar
     * @param sampleSize   alarm satırına yazılan en eski kayıt sayısı
     */
    public record StuckDetection(
            @DefaultValue("1m") Duration interval,
            @DefaultValue("30m") Duration received,
            @DefaultValue("10m") Duration validated,
            @DefaultValue("30m") Duration queuedForRpa,
            @DefaultValue("10") int sampleSize) {
    }

    /**
     * @param cron        Spring cron ifadesi; varsayılan her gece 03:00
     * @param gracePeriod bundan yeni dosyalara dokunulmaz: dosya commit'ten önce yerine taşındığı için (B-10)
     *                    commit olmak üzere olan kaydın dosyası silinmesin
     */
    public record OrphanCleanup(
            @DefaultValue("0 0 3 * * *") String cron,
            @DefaultValue("1h") Duration gracePeriod) {
    }

    /**
     * Uzman düzeltmesi extraction-service'in aritmetik kurallarıyla doğrulanır (FR-E3, B-40); varsayılanlar
     * {@code invoice.extraction.validation.*} ile aynıdır.
     *
     * @param perLineTolerance kalem başına yuvarlama payı; tolerans max({@code minimumTolerance}, kalem sayısı × bu)
     * @param minimumTolerance ölçekli toleransın alt sınırı
     * @param totalTolerance   ara toplam + KDV = genel toplam için sabit tolerans
     */
    public record Correction(
            @DefaultValue("0.01") BigDecimal perLineTolerance,
            @DefaultValue("0.02") BigDecimal minimumTolerance,
            @DefaultValue("0.02") BigDecimal totalTolerance) {
    }
}
