package com.archosan.invoice.compliance.ingest;

import com.archosan.invoice.messaging.consumer.InvoiceListenerContainers;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.message.IngestContract;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code compliance.ingest-contract.q} (uzun iş, 1 · 1, DECISIONS.md §5) ve DLQ'su (1 · 1). Topoloji
 * {@code definitions.json}'dadır (ADR-17).
 */
@Configuration(proxyBeanMethods = false)
class IngestListenerConfiguration {

    static final String QUEUE = "compliance.ingest-contract.q";
    static final String DEAD_LETTER_QUEUE = "compliance.ingest-contract.dlq";

    @Bean
    SimpleMessageListenerContainer ingestContractContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, IngestContractHandler handler) {
        return InvoiceListenerContainers.create(connectionFactory,
                listeners.forQueue(QUEUE).onLongRunning(IngestContract.class, handler).build(), 1, 1);
    }

    @Bean
    SimpleMessageListenerContainer ingestContractDeadLetterContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, IngestDeadLetterHandler handler) {
        return InvoiceListenerContainers.create(connectionFactory,
                listeners.forDeadLetterQueue(DEAD_LETTER_QUEUE, handler::handle), 1, 1);
    }
}
