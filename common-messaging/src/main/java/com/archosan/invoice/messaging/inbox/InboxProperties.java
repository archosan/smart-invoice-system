package com.archosan.invoice.messaging.inbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Inbox ayarları ({@code invoice.messaging.inbox.*}).
 *
 * @param retention          bu süreden eski satırlar silinir; RabbitMQ'nun bu kadar geç tekrar teslimi pratikte yoktur
 * @param cleanupInterval    iki temizlik turu arası
 * @param cleanupBatchSize   tek DELETE'in sildiği en fazla satır
 * @param cleanup            temizliğin açılıp açılmayacağı ve kendiliğinden başlayıp başlamayacağı
 */
@ConfigurationProperties("invoice.messaging.inbox")
public record InboxProperties(
        @DefaultValue("30d") Duration retention,
        @DefaultValue("1h") Duration cleanupInterval,
        @DefaultValue("10000") int cleanupBatchSize,
        @DefaultValue Cleanup cleanup) {

    public record Cleanup(@DefaultValue("true") boolean enabled, @DefaultValue("true") boolean autoStartup) {
    }
}
