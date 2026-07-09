package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.CorrelationScope;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.ReceivedMessage;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bir kuyruğun dinleyicisi: mesajı çözer, inbox kontrolüyle tipine göre handler'ı çağırır ve sonucu kendisi
 * ack / reject eder.
 *
 * <p><b>Ack / reject.</b> Container'ın kendi hata yolu kullanılmaz: Spring AMQP istisnada {@code basicNack} gönderir
 * ve RabbitMQ 4.3'te {@code nack} quorum kuyruğun teslim sayacını artırmaz; hatalı mesaj DLQ'ya hiç düşmez
 * (DECISIONS.md §5, ADR-19). Bu yüzden container {@code AcknowledgeMode.MANUAL} ile çalışır
 * ({@link InvoiceListenerContainers}) ve bu sınıf:
 * <ul>
 *   <li>başarıda ve tekrar gelen mesajda {@code basicAck},</li>
 *   <li>zehirli mesajda (çözümlenemeyen, bu kuyrukta handler'ı olmayan tip,
 *       {@link AmqpRejectAndDontRequeueException}) {@code basicReject(requeue=false)} → doğrudan DLQ,</li>
 *   <li>diğer istisnalarda {@code basicReject(requeue=true)} → teslim sayacı artar, limitten sonra DLQ</li>
 * </ul>
 * gönderir. Handler istisnaları dışarı sızmaz; yalnızca kanal hatası ({@link IOException}) container'a döner.
 *
 * <p><b>Inbox (ADR-05).</b> {@code consumer} kuyruk adıdır. {@link MessageHandler} (kısa iş) bir transaction içinde,
 * {@code tryInsert}'ten sonra çağrılır. {@link LongRunningMessageHandler} (LLM, RPA) için önce transaction dışında
 * {@code exists} bakılır, iş transaction dışında yapılır, sonuç {@link InboxCompletion} ile kısa bir transaction'da
 * {@code tryInsert}'le yazılır; handler {@code complete} ya da {@code defer} çağırmadan dönerse bu bir programlama
 * hatasıdır ve mesaj kuyruğa geri konur. {@code defer} işi inbox'a yazmadan commit edip ack eder: mesaj aynı kimlikle
 * bir bekleme odasından geri gelecektir (B-34). Tekrar gelen mesajda handler çağrılmaz, mesaj ack edilir.
 *
 * <p><b>Log (NFR-06).</b> İşlem boyunca MDC'de {@code correlationId}, {@code messageId} ve {@code messageType} vardır
 * ({@link CorrelationScope}); handler'ın yazdığı her log satırı bunları taşır.
 */
public final class InvoiceMessageListener implements ChannelAwareMessageListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceMessageListener.class);

    /** {@code ACK}, {@code DUPLICATE} ve {@code DEFERRED} ack edilir; ayrım yalnız metrik içindir (B-48). */
    enum Outcome {
        ACK,
        DUPLICATE,
        DEFERRED,
        REQUEUE,
        DEAD_LETTER
    }

    private sealed interface Registration permits ShortRunning, LongRunning {
    }

    private record ShortRunning(MessageHandler<?> handler) implements Registration {
    }

    private record LongRunning(LongRunningMessageHandler<?> handler) implements Registration {
    }

    private final String queue;
    private final InvoiceMessageConverter converter;
    private final Inbox inbox;
    private final TransactionTemplate transactions;
    private final Map<MessageType, Registration> registrations;
    private final MessagingMetrics metrics;

    private InvoiceMessageListener(Builder builder) {
        this.queue = builder.queue;
        this.converter = builder.converter;
        this.inbox = builder.inbox;
        this.transactions = new TransactionTemplate(builder.transactionManager);
        this.registrations = Map.copyOf(builder.registrations);
        this.metrics = builder.metrics;
    }

    /**
     * Servisler genellikle auto-config'in sağladığı {@link InvoiceMessageListenerFactory}'yi kullanır.
     *
     * @param queue dinlenen kuyruk; inbox'ta {@code consumer} olarak da kullanılır
     */
    public static Builder builder(String queue, InvoiceMessageConverter converter, Inbox inbox,
            PlatformTransactionManager transactionManager) {
        return new Builder(queue, converter, inbox, transactionManager);
    }

    public String queue() {
        return queue;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        long start = System.nanoTime();
        Outcome outcome = dispatch(message);
        metrics.processed(queue, message.getMessageProperties().getType(), outcome.name(), System.nanoTime() - start);
        switch (outcome) {
            case ACK, DUPLICATE, DEFERRED -> channel.basicAck(deliveryTag, false);
            case REQUEUE -> channel.basicReject(deliveryTag, true);
            case DEAD_LETTER -> channel.basicReject(deliveryTag, false);
        }
    }

    Outcome dispatch(Message message) {
        MessageProperties props = message.getMessageProperties();
        // Ham AMQP alanlarından: zehirli mesajın logları da eldeki kimlikleri taşısın.
        try (CorrelationScope ignored = CorrelationScope.forMessage(
                props.getCorrelationId(), props.getMessageId(), props.getType())) {
            return dispatchInScope(message, props);
        }
    }

    private Outcome dispatchInScope(Message message, MessageProperties props) {
        ReceivedMessage received;
        try {
            received = converter.fromAmqpMessage(message);
        } catch (MessageConversionException e) {
            log.warn("Zehirli mesaj DLQ'ya gönderiliyor: messageId={}, type={}, neden={}",
                    props.getMessageId(), props.getType(), e.getMessage());
            return Outcome.DEAD_LETTER;
        }

        Registration registration = registrations.get(received.envelope().type());
        if (registration == null) {
            log.warn("Bu kuyrukta handler'ı olmayan tip DLQ'ya gönderiliyor: messageId={}, type={}, kuyruk={}",
                    props.getMessageId(), props.getType(), queue);
            return Outcome.DEAD_LETTER;
        }

        try {
            return switch (registration) {
                case ShortRunning r -> processShortRunning(r.handler(), received);
                case LongRunning r -> processLongRunning(r.handler(), received);
            };
        } catch (Exception e) {
            if (isRejectAndDontRequeue(e)) {
                log.warn("Kalıcı hata, mesaj DLQ'ya gönderiliyor: messageId={}, type={}",
                        props.getMessageId(), props.getType(), e);
                return Outcome.DEAD_LETTER;
            }
            log.error("İşlenemedi, mesaj kuyruğa geri konuyor: messageId={}, type={}, x-delivery-count={}",
                    props.getMessageId(), props.getType(), props.getHeader("x-delivery-count"), e);
            return Outcome.REQUEUE;
        }
    }

    private Outcome processShortRunning(MessageHandler<?> handler, ReceivedMessage received) {
        boolean processed = Boolean.TRUE.equals(transactions.execute(status -> {
            if (!inbox.tryInsert(received.envelope().messageId(), queue)) {
                return false;
            }
            invoke(handler, received);
            return true;
        }));
        if (!processed) {
            logDuplicate(received);
            return Outcome.DUPLICATE;
        }
        return Outcome.ACK;
    }

    private Outcome processLongRunning(LongRunningMessageHandler<?> handler, ReceivedMessage received) {
        if (inbox.exists(received.envelope().messageId(), queue)) {
            logDuplicate(received);
            return Outcome.DUPLICATE;
        }
        Completion completion = new Completion(received);
        invoke(handler, received, completion);
        if (!completion.called) {
            // Programlama hatası: sonuç inbox kaydıyla aynı transaction'da yazılmadı. Sessizce ack etmek yerine
            // mesaj geri konur; teslim limitinden sonra DLQ'ya düşer ve hata görünür olur.
            throw new IllegalStateException("Uzun iş handler'ı complete() ya da defer() çağırmadan döndü: "
                    + received.envelope().type().typeName());
        }
        return completion.deferred ? Outcome.DEFERRED : Outcome.ACK;
    }

    private void logDuplicate(ReceivedMessage received) {
        log.info("Daha önce işlenmiş mesaj atlandı (inbox): messageId={}, type={}, kuyruk={}",
                received.envelope().messageId(), received.envelope().type().typeName(), queue);
    }

    @SuppressWarnings("unchecked")
    private static <T extends InvoiceMessage> void invoke(MessageHandler<T> handler, ReceivedMessage received) {
        handler.handle(received.envelope(), (T) received.payload());
    }

    @SuppressWarnings("unchecked")
    private static <T extends InvoiceMessage> void invoke(LongRunningMessageHandler<T> handler,
            ReceivedMessage received, InboxCompletion completion) {
        handler.handle(received.envelope(), (T) received.payload(), completion);
    }

    private static boolean isRejectAndDontRequeue(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
        }
        return false;
    }

    private final class Completion implements InboxCompletion {

        private final ReceivedMessage received;
        private boolean called;
        private boolean deferred;

        private Completion(ReceivedMessage received) {
            this.received = received;
        }

        @Override
        public boolean complete(Runnable work) {
            markCalled();
            boolean applied = Boolean.TRUE.equals(transactions.execute(status -> {
                if (!inbox.tryInsert(received.envelope().messageId(), queue)) {
                    return false;
                }
                work.run();
                return true;
            }));
            if (!applied) {
                log.info("Mesaj bu arada başka bir kopyada işlenmiş, sonuç atıldı: messageId={}, type={}, kuyruk={}",
                        received.envelope().messageId(), received.envelope().type().typeName(), queue);
            }
            return applied;
        }

        @Override
        public void defer(Runnable work) {
            markCalled();
            deferred = true;
            transactions.executeWithoutResult(status -> work.run());
            log.info("Mesaj ertelendi, inbox'a yazılmadı (aynı kimlikle geri gelecek): messageId={}, type={}, "
                            + "kuyruk={}",
                    received.envelope().messageId(), received.envelope().type().typeName(), queue);
        }

        private void markCalled() {
            if (called) {
                throw new IllegalStateException("complete() veya defer() bir kez çağrılır");
            }
            called = true;
        }
    }

    public static final class Builder {

        private final String queue;
        private final InvoiceMessageConverter converter;
        private final Inbox inbox;
        private final PlatformTransactionManager transactionManager;
        private final Map<MessageType, Registration> registrations = new EnumMap<>(MessageType.class);
        private MessagingMetrics metrics = MessagingMetrics.NONE;

        private Builder(String queue, InvoiceMessageConverter converter, Inbox inbox,
                PlatformTransactionManager transactionManager) {
            this.queue = Objects.requireNonNull(queue, "queue");
            this.converter = Objects.requireNonNull(converter, "converter");
            this.inbox = Objects.requireNonNull(inbox, "inbox");
            this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
        }

        /** Kısa iş: handler transaction içinde, inbox kaydından sonra çağrılır. */
        public <T extends InvoiceMessage> Builder on(Class<T> payloadClass, MessageHandler<T> handler) {
            return register(payloadClass, new ShortRunning(handler));
        }

        /** Uzun iş (LLM, RPA): iş transaction dışında, sonuç {@link InboxCompletion} ile yazılır. */
        public <T extends InvoiceMessage> Builder onLongRunning(Class<T> payloadClass,
                LongRunningMessageHandler<T> handler) {
            return register(payloadClass, new LongRunning(handler));
        }

        private Builder register(Class<? extends InvoiceMessage> payloadClass, Registration registration) {
            MessageType type = MessageType.of(payloadClass);
            if (registrations.putIfAbsent(type, registration) != null) {
                throw new IllegalStateException("Aynı tip için ikinci handler: " + type.typeName());
            }
            return this;
        }

        /** Ölçüm (B-48); verilmezse no-op. */
        public Builder metrics(MessagingMetrics metrics) {
            this.metrics = Objects.requireNonNull(metrics, "metrics");
            return this;
        }

        public InvoiceMessageListener build() {
            if (registrations.isEmpty()) {
                throw new IllegalStateException("En az bir handler gerekli");
            }
            return new InvoiceMessageListener(this);
        }
    }
}
