package com.archosan.invoice.document.deadletter;

import com.archosan.invoice.messaging.consumer.InvoiceListenerContainers;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Üç komut DLQ'sunun container'ları (prefetch 1, eşzamanlılık 1); dinleyici hiçbir mesajı düşürmez. */
@Configuration(proxyBeanMethods = false)
class DeadLetterListenersConfiguration {

    @Bean
    SimpleMessageListenerContainer extractInvoiceDeadLetterContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, CommandDeadLetterHandler handler) {
        return container(connectionFactory, listeners, handler, DeadLetterQueues.EXTRACT_INVOICE);
    }

    @Bean
    SimpleMessageListenerContainer checkComplianceDeadLetterContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, CommandDeadLetterHandler handler) {
        return container(connectionFactory, listeners, handler, DeadLetterQueues.CHECK_COMPLIANCE);
    }

    @Bean
    SimpleMessageListenerContainer postToPortalDeadLetterContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, CommandDeadLetterHandler handler) {
        return container(connectionFactory, listeners, handler, DeadLetterQueues.POST_TO_PORTAL);
    }

    private static SimpleMessageListenerContainer container(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, CommandDeadLetterHandler handler, String queue) {
        return InvoiceListenerContainers.create(connectionFactory,
                listeners.forDeadLetterQueue(queue, handler::handle), 1, 1);
    }
}
