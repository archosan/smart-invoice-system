package com.archosan.invoice.messaging.autoconfigure;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.inbox.InboxCleaner;
import com.archosan.invoice.messaging.inbox.InboxProperties;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Inbox ve dinleyici fabrikası. Servis JDBC'yi eklemediyse (henüz DB'si yoksa) hiçbiri kurulmaz. Migration
 * ({@code V0_2__inbox.sql}) ortak konumdan gelir ({@link OutboxAutoConfiguration}).
 */
@AutoConfiguration(
        after = InvoiceMessagingAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration"
        })
@ConditionalOnClass(JdbcClient.class)
@EnableConfigurationProperties(InboxProperties.class)
public class InboxAutoConfiguration {

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    Inbox inbox(JdbcClient jdbc) {
        return new Inbox(jdbc);
    }

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    @ConditionalOnBooleanProperty(name = "invoice.messaging.inbox.cleanup.enabled", matchIfMissing = true)
    InboxCleaner inboxCleaner(Inbox inbox, InboxProperties properties) {
        return new InboxCleaner(inbox, properties);
    }

    @Bean
    @ConditionalOnBean({JdbcClient.class, PlatformTransactionManager.class})
    InvoiceMessageListenerFactory invoiceMessageListenerFactory(InvoiceMessageConverter converter, Inbox inbox,
            PlatformTransactionManager transactionManager, MessagingMetrics metrics) {
        return new InvoiceMessageListenerFactory(converter, inbox, transactionManager, metrics);
    }
}
