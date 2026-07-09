package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Dinleyici kurmanın kısa yolu; auto-config tarafından sağlanır.
 *
 * <pre>{@code
 * var listener = listeners.forQueue("document.extraction-events.q")
 *         .on(ExtractionCompleted.class, this::onCompleted)
 *         .on(ExtractionFailed.class, this::onFailed)
 *         .build();
 * InvoiceListenerContainers.create(connectionFactory, listener, 10, 2);
 * }</pre>
 */
public final class InvoiceMessageListenerFactory {

    private final InvoiceMessageConverter converter;
    private final Inbox inbox;
    private final PlatformTransactionManager transactionManager;
    private final MessagingMetrics metrics;

    public InvoiceMessageListenerFactory(InvoiceMessageConverter converter, Inbox inbox,
            PlatformTransactionManager transactionManager) {
        this(converter, inbox, transactionManager, MessagingMetrics.NONE);
    }

    public InvoiceMessageListenerFactory(InvoiceMessageConverter converter, Inbox inbox,
            PlatformTransactionManager transactionManager, MessagingMetrics metrics) {
        this.converter = converter;
        this.inbox = inbox;
        this.transactionManager = transactionManager;
        this.metrics = metrics;
    }

    public InvoiceMessageListener.Builder forQueue(String queue) {
        return InvoiceMessageListener.builder(queue, converter, inbox, transactionManager).metrics(metrics);
    }

    /** DLQ dinleyicisi; hiçbir mesajı düşürmez ({@link DeadLetterListener}). */
    public DeadLetterListener forDeadLetterQueue(String queue, DeadLetterHandler handler) {
        return new DeadLetterListener(queue, converter, inbox, transactionManager, handler, metrics);
    }
}
