package com.archosan.invoice.compliance.check;

import com.archosan.invoice.messaging.consumer.InvoiceListenerContainers;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.message.CheckCompliance;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code compliance.check-compliance.q}: prefetch 1, eşzamanlılık 1 (DECISIONS.md §5), uzun iş (B-46: embedding ve
 * LLM). Sözleşme indeksleme kuyruğu {@code ingest} paketinde.
 */
@Configuration(proxyBeanMethods = false)
class ComplianceListenerConfiguration {

    static final String QUEUE = "compliance.check-compliance.q";

    @Bean
    SimpleMessageListenerContainer checkComplianceContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, CheckComplianceHandler handler) {
        return InvoiceListenerContainers.create(connectionFactory,
                listeners.forQueue(QUEUE).onLongRunning(CheckCompliance.class, handler).build(), 1, 1);
    }
}
