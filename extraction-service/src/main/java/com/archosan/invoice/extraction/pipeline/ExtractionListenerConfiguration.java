package com.archosan.invoice.extraction.pipeline;

import com.archosan.invoice.messaging.consumer.InvoiceListenerContainers;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** {@code extraction.extract-invoice.q}: prefetch 1, eşzamanlılık 1 (LLM sınırı, DECISIONS.md §5). */
@Configuration(proxyBeanMethods = false)
class ExtractionListenerConfiguration {

    static final String QUEUE = "extraction.extract-invoice.q";

    @Bean
    SimpleMessageListenerContainer extractInvoiceContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, ExtractInvoiceHandler handler) {
        return InvoiceListenerContainers.create(connectionFactory,
                listeners.forQueue(QUEUE).onLongRunning(ExtractInvoice.class, handler).build(), 1, 1);
    }
}
