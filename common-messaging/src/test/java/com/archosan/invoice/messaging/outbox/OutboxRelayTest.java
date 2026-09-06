package com.archosan.invoice.messaging.outbox;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    @Test
    void refusesToStartWithoutPublisherConfirms() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.isPublisherConfirms()).thenReturn(false);
        when(connectionFactory.isPublisherReturns()).thenReturn(true);
        OutboxRelay relay = relay(connectionFactory);

        assertThatThrownBy(relay::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publisher-confirm-type=correlated");
        assertThat(relay.isRunning()).isFalse();
    }

    @Test
    void refusesToStartWithoutPublisherReturns() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.isPublisherConfirms()).thenReturn(true);
        when(connectionFactory.isPublisherReturns()).thenReturn(false);

        assertThatThrownBy(relay(connectionFactory)::start).isInstanceOf(IllegalStateException.class);
    }

    private static OutboxRelay relay(ConnectionFactory connectionFactory) {
        return new OutboxRelay(mock(JdbcClient.class), mock(PlatformTransactionManager.class), connectionFactory,
                new InvoiceMessageConverter(),
                new OutboxProperties(Duration.ofMillis(500), 100, Duration.ofSeconds(5),
                        new OutboxProperties.Relay(true, false), Duration.ofDays(7), Duration.ofHours(1), 10_000,
                        new OutboxProperties.Cleanup(true, false)));
    }
}
