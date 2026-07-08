package com.archosan.invoice.messaging.consumer;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;

/**
 * {@link InvoiceMessageListener} için container üretir. Ack modu MANUAL'dır: bu modda Spring AMQP istisnada
 * kendisi nack göndermez, ack / reject kararı tamamen dinleyicidedir. Kuyruk dinleyiciden alınır; inbox'taki
 * {@code consumer} değeriyle aynı kalsın diye.
 */
public final class InvoiceListenerContainers {

    private InvoiceListenerContainers() {
    }

    public static SimpleMessageListenerContainer create(ConnectionFactory connectionFactory,
            InvoiceMessageListener listener, int prefetch, int concurrency) {
        return create(connectionFactory, listener.queue(), listener, prefetch, concurrency);
    }

    public static SimpleMessageListenerContainer create(ConnectionFactory connectionFactory,
            DeadLetterListener listener, int prefetch, int concurrency) {
        return create(connectionFactory, listener.queue(), listener, prefetch, concurrency);
    }

    private static SimpleMessageListenerContainer create(ConnectionFactory connectionFactory, String queue,
            ChannelAwareMessageListener listener, int prefetch, int concurrency) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queue);
        container.setMessageListener(listener);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(prefetch);
        container.setConcurrentConsumers(concurrency);
        return container;
    }
}
