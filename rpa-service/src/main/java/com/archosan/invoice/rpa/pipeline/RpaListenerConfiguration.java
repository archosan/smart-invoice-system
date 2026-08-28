package com.archosan.invoice.rpa.pipeline;

import com.archosan.invoice.messaging.consumer.InvoiceListenerContainers;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.rpa.RpaProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code rpa.post-to-portal.q}: prefetch 1, eşzamanlılık 1; kuyruk single active consumer'dır (FR-R8, ADR-08), ikinci
 * örnek yedekte bekler (DECISIONS.md §5).
 *
 * <p>Kapanışta sürmekte olan giriş sonuna kadar beklenir ({@code shutdownTimeout} = girişin üst sınırı; varsayılan 5
 * sn'dir). Daha kısa beklenirse kanal kapanır, mesaj yedek örneğe geçer ve bu örnek aynı faturayı girmeye devam
 * ederken yedek de girebilir (B-27). Spring'in kapanış aşaması ve compose'un {@code stop_grace_period}'u bundan uzun
 * olmalıdır.
 */
@Configuration(proxyBeanMethods = false)
class RpaListenerConfiguration {

    static final String QUEUE = "rpa.post-to-portal.q";

    @Bean
    SimpleMessageListenerContainer postToPortalContainer(ConnectionFactory connectionFactory,
            InvoiceMessageListenerFactory listeners, PostToPortalHandler handler, RpaProperties properties) {
        SimpleMessageListenerContainer container = InvoiceListenerContainers.create(connectionFactory,
                listeners.forQueue(QUEUE).onLongRunning(PostToPortal.class, handler).build(), 1, 1);
        container.setShutdownTimeout(properties.portal().maxProcessingTime().toMillis());
        return container;
    }
}
