package com.archosan.invoice.messaging.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Relay ve temizlik ayarları ({@code invoice.messaging.outbox.*}).
 *
 * @param pollInterval     iki tur arası bekleme; tam dolu ve tamamen yayınlanmış bir partiden sonra beklenmez
 * @param batchSize        bir turda kilitlenen en fazla satır
 * @param confirmTimeout   bir partinin tüm publisher confirm'leri için toplam bekleme
 * @param relay            relay'in açılıp açılmayacağı ve kendiliğinden başlayıp başlamayacağı
 * @param retention        yayınlanmış satırlar bu süreden sonra silinir (A7); yayınlanmamış satırlar hiç silinmez
 * @param cleanupInterval  iki temizlik turu arası
 * @param cleanupBatchSize tek DELETE'in sildiği en fazla satır
 * @param cleanup          temizliğin açılıp açılmayacağı ve kendiliğinden başlayıp başlamayacağı
 */
@ConfigurationProperties("invoice.messaging.outbox")
public record OutboxProperties(
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue Relay relay,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("1h") Duration cleanupInterval,
        @DefaultValue("10000") int cleanupBatchSize,
        @DefaultValue Cleanup cleanup) {

    public record Relay(@DefaultValue("true") boolean enabled, @DefaultValue("true") boolean autoStartup) {
    }

    public record Cleanup(@DefaultValue("true") boolean enabled, @DefaultValue("true") boolean autoStartup) {
    }
}
