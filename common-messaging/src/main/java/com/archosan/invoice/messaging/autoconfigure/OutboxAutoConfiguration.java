package com.archosan.invoice.messaging.autoconfigure;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.outbox.OutboxBacklogMetrics;
import com.archosan.invoice.messaging.outbox.OutboxCleaner;
import com.archosan.invoice.messaging.outbox.OutboxProperties;
import com.archosan.invoice.messaging.outbox.OutboxRelay;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Outbox bileşenleri. Servis JDBC'yi eklemediyse (henüz DB'si yoksa) hiçbiri kurulmaz.
 */
@AutoConfiguration(
        after = InvoiceMessagingAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
                "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@ConditionalOnClass(JdbcClient.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    static final String COMMON_MIGRATIONS = "classpath:db/migration/common";

    @Bean
    @ConditionalOnBean(JdbcClient.class)
    OutboxWriter outboxWriter(JdbcClient jdbc, InvoiceMessageConverter converter) {
        return new OutboxWriter(jdbc, converter);
    }

    @Bean
    @ConditionalOnBean({JdbcClient.class, PlatformTransactionManager.class, ConnectionFactory.class})
    @ConditionalOnBooleanProperty(name = "invoice.messaging.outbox.relay.enabled", matchIfMissing = true)
    OutboxRelay outboxRelay(JdbcClient jdbc, PlatformTransactionManager transactionManager,
            ConnectionFactory connectionFactory, InvoiceMessageConverter converter, OutboxProperties properties,
            MessagingMetrics metrics) {
        return new OutboxRelay(jdbc, transactionManager, connectionFactory, converter, properties, metrics);
    }

    /** A7: yayınlanmış satırların saklama süresi sonunda silinmesi. */
    @Bean
    @ConditionalOnBean(JdbcClient.class)
    @ConditionalOnBooleanProperty(name = "invoice.messaging.outbox.cleanup.enabled", matchIfMissing = true)
    OutboxCleaner outboxCleaner(JdbcClient jdbc, OutboxProperties properties) {
        return new OutboxCleaner(jdbc, properties);
    }

    /** B-48: outbox birikimi; kayıt defteri (Actuator) varsa bağlanır. */
    @Bean
    @ConditionalOnBean(JdbcClient.class)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    OutboxBacklogMetrics outboxBacklogMetrics(JdbcClient jdbc) {
        return new OutboxBacklogMetrics(jdbc);
    }

    /**
     * Ortak migration'ları ({@code V0_x}) servisin Flyway konumlarına ekler. Varsayılan {@code classpath:db/migration}
     * özyinelemeli tarandığı için onları zaten kapsar; bu, servis konumları değiştirirse outbox tablosu eksik
     * kalmasın diyedir.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Flyway.class)
    static class CommonMigrationsConfiguration {

        @Bean
        FlywayConfigurationCustomizer commonMessagingMigrations() {
            return configuration -> {
                List<String> locations = new ArrayList<>(Arrays.stream(configuration.getLocations())
                        .map(Location::getDescriptor)
                        .toList());
                if (!locations.contains(COMMON_MIGRATIONS)) {
                    locations.add(COMMON_MIGRATIONS);
                    configuration.locations(locations.toArray(String[]::new));
                }
            };
        }
    }
}
