package com.archosan.invoice.messaging.autoconfigure;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.inbox.InboxCleaner;
import com.archosan.invoice.messaging.outbox.OutboxRelay;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MessagingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    InvoiceMessagingAutoConfiguration.class, OutboxAutoConfiguration.class,
                    InboxAutoConfiguration.class));

    private final ApplicationContextRunner withInfrastructure = runner
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withPropertyValues("invoice.messaging.outbox.relay.auto-startup=false",
                    "invoice.messaging.inbox.cleanup.auto-startup=false");

    @Test
    void createsWriterAndRelayWhenInfrastructureIsPresent() {
        withInfrastructure.run(context -> {
            assertThat(context).hasSingleBean(InvoiceMessageConverter.class);
            assertThat(context).hasSingleBean(OutboxWriter.class);
            assertThat(context).hasSingleBean(OutboxRelay.class);
            assertThat(context).hasSingleBean(Inbox.class);
            assertThat(context).hasSingleBean(InboxCleaner.class);
            assertThat(context).hasSingleBean(InvoiceMessageListenerFactory.class);
        });
    }

    @Test
    void inboxCleanupCanBeDisabled() {
        withInfrastructure.withPropertyValues("invoice.messaging.inbox.cleanup.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(Inbox.class);
            assertThat(context).doesNotHaveBean(InboxCleaner.class);
        });
    }

    @Test
    void relayCanBeDisabled() {
        withInfrastructure.withPropertyValues("invoice.messaging.outbox.relay.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(OutboxWriter.class);
            assertThat(context).doesNotHaveBean(OutboxRelay.class);
        });
    }

    @Test
    void createsNothingWithoutDatabase() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InvoiceMessageConverter.class);
            assertThat(context).doesNotHaveBean(OutboxWriter.class);
            assertThat(context).doesNotHaveBean(OutboxRelay.class);
            assertThat(context).doesNotHaveBean(Inbox.class);
            assertThat(context).doesNotHaveBean(InvoiceMessageListenerFactory.class);
        });
    }

    @Test
    void backsOffWhenJdbcIsNotOnClasspath() {
        runner.withClassLoader(new FilteredClassLoader(JdbcClient.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OutboxWriter.class);
            assertThat(context).doesNotHaveBean(Inbox.class);
        });
    }

    @Test
    void addsCommonMigrationsOnceWhenServiceUsesCustomLocation() {
        withInfrastructure.run(context -> {
            FlywayConfigurationCustomizer customizer = context.getBean(FlywayConfigurationCustomizer.class);
            FluentConfiguration configuration = Flyway.configure().locations("classpath:db/document");

            customizer.customize(configuration);
            customizer.customize(configuration);

            assertThat(configuration.getLocations())
                    .extracting(Location::getDescriptor)
                    .containsExactly("classpath:db/document", "classpath:db/migration/common");
        });
    }
}
