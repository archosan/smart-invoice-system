package com.archosan.invoice.document.events;

import com.archosan.invoice.document.DocumentProperties;
import com.archosan.invoice.messaging.consumer.InvoiceListenerContainers;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.message.ExtractionCompleted;
import com.archosan.invoice.messaging.message.ExtractionFailed;
import com.archosan.invoice.messaging.message.RpaCompleted;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Üç olay kuyruğunun container'ları; MANUAL ack, inbox ve tek transaction dinleyicide (ADR-19, B-06). */
@Configuration(proxyBeanMethods = false)
class EventListenersConfiguration {

    @Bean
    SimpleMessageListenerContainer extractionEventsContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, ExtractionEventHandler handler, DocumentProperties properties) {
        return InvoiceListenerContainers.create(connectionFactory,
                listeners.forQueue(DocumentQueues.EXTRACTION_EVENTS)
                        .on(ExtractionCompleted.class, handler::onCompleted)
                        .on(ExtractionFailed.class, handler::onFailed)
                        .build(),
                properties.listener().prefetch(), properties.listener().concurrency());
    }

    @Bean
    SimpleMessageListenerContainer complianceEventsContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, ComplianceEventHandler handler, DocumentProperties properties) {
        return InvoiceListenerContainers.create(connectionFactory,
                listeners.forQueue(DocumentQueues.COMPLIANCE_EVENTS)
                        .on(ComplianceCompleted.class, handler::onCompleted)
                        .build(),
                properties.listener().prefetch(), properties.listener().concurrency());
    }

    @Bean
    SimpleMessageListenerContainer rpaEventsContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, RpaEventHandler handler, DocumentProperties properties) {
        return InvoiceListenerContainers.create(connectionFactory,
                listeners.forQueue(DocumentQueues.RPA_EVENTS)
                        .on(RpaCompleted.class, handler::onCompleted)
                        .build(),
                properties.listener().prefetch(), properties.listener().concurrency());
    }
}
