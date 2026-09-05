package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InvoiceListenerContainersTest {

    @Test
    void createsContainerInManualAckModeOnListenersQueue() {
        InvoiceMessageListener listener = new InvoiceMessageListenerFactory(new InvoiceMessageConverter(),
                mock(Inbox.class), mock(PlatformTransactionManager.class))
                .forQueue("extraction.extract-invoice.q")
                .on(ExtractInvoice.class, (envelope, message) -> { })
                .build();

        SimpleMessageListenerContainer container = InvoiceListenerContainers.create(
                mock(ConnectionFactory.class), listener, 1, 1);

        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(container.getQueueNames()).containsExactly("extraction.extract-invoice.q");
        assertThat(container.getMessageListener()).isSameAs(listener);
    }
}
