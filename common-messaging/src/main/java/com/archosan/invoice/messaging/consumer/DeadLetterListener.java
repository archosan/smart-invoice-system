package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.CorrelationScope;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.ReceivedMessage;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * DLQ dinleyicisi (B-14). {@link InvoiceMessageListener}'dan farkı: DLQ'ların kendi DLX'i yoktur, reddedilen mesajı
 * broker siler. Bu yüzden bu dinleyici <b>hiçbir mesajı düşürmez</b>:
 * <ul>
 *   <li>mesaj katı biçimde çözülmez; çözülemeyen (zehirli) mesaj da ham haliyle handler'a verilir,</li>
 *   <li>başarıda {@code basicAck}, her hatada {@code basicReject(requeue=true)}; DLQ'larda teslim limiti yoktur
 *       ({@code x-delivery-limit = -1}), mesaj sorun düzelene kadar bekler.</li>
 * </ul>
 * Inbox anahtarı mesajın {@code messageId}'sidir, {@code consumer} DLQ adıdır; {@code messageId} yoksa veya UUID
 * değilse inbox atlanır ve uyarı loglanır. İşlem boyunca MDC'de ham kimlikler vardır.
 */
public final class DeadLetterListener implements ChannelAwareMessageListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final String queue;
    private final InvoiceMessageConverter converter;
    private final Inbox inbox;
    private final TransactionTemplate transactions;
    private final DeadLetterHandler handler;
    private final MessagingMetrics metrics;

    public DeadLetterListener(String queue, InvoiceMessageConverter converter, Inbox inbox,
            PlatformTransactionManager transactionManager, DeadLetterHandler handler) {
        this(queue, converter, inbox, transactionManager, handler, MessagingMetrics.NONE);
    }

    /** @param metrics yeni (inbox'ta olmayan) her DLQ mesajı sayılır (B-48) */
    public DeadLetterListener(String queue, InvoiceMessageConverter converter, Inbox inbox,
            PlatformTransactionManager transactionManager, DeadLetterHandler handler, MessagingMetrics metrics) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.handler = Objects.requireNonNull(handler, "handler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public String queue() {
        return queue;
    }

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        MessageProperties props = message.getMessageProperties();
        boolean processed;
        try (CorrelationScope ignored = CorrelationScope.forMessage(
                props.getCorrelationId(), props.getMessageId(), props.getType())) {
            processed = process(message, props);
        }
        if (processed) {
            channel.basicAck(props.getDeliveryTag(), false);
        } else {
            channel.basicReject(props.getDeliveryTag(), true);
        }
    }

    /** @return {@code true} ise ack, {@code false} ise mesaj DLQ'ya geri konur */
    boolean process(Message message, MessageProperties props) {
        DeadLetter deadLetter = toDeadLetter(message, props);
        Optional<UUID> messageId = deadLetter.messageIdAsUuid();
        if (messageId.isEmpty()) {
            log.warn("DLQ mesajının geçerli message_id'si yok, inbox atlanıyor: kuyruk={}", queue);
        }
        try {
            boolean handled = Boolean.TRUE.equals(transactions.execute(status -> {
                if (messageId.isPresent() && !inbox.tryInsert(messageId.get(), queue)) {
                    return false;
                }
                handler.handle(deadLetter);
                return true;
            }));
            if (handled) {
                metrics.deadLetterReceived(queue, deadLetter.type());
            } else {
                log.info("Daha önce işlenmiş DLQ mesajı atlandı (inbox): kuyruk={}", queue);
            }
            return true;
        } catch (RuntimeException e) {
            log.error("DLQ mesajı işlenemedi, DLQ'ya geri konuyor: kuyruk={}", queue, e);
            return false;
        }
    }

    private DeadLetter toDeadLetter(Message message, MessageProperties props) {
        ReceivedMessage received = null;
        try {
            received = converter.fromAmqpMessage(message);
        } catch (MessageConversionException e) {
            log.warn("DLQ mesajı çözülemedi, ham haliyle işlenecek: kuyruk={}, neden={}", queue, e.getMessage());
        }
        List<Map<String, ?>> xDeath = props.getXDeathHeader();
        return new DeadLetter(queue, props.getMessageId(), props.getType(), props.getCorrelationId(),
                message.getBody(), lastOrFirst(props, "queue"), lastOrFirst(props, "reason"),
                xDeath == null ? List.of() : xDeath,
                received == null ? null : received.envelope(), received == null ? null : received.payload());
    }

    /**
     * DLQ'ya düşüren (son) ölüm: {@code x-last-death-*} (RabbitMQ 3.13+), yoksa {@code x-first-death-*}. Bekleme
     * odasından geçen mesajın ilk ölümü oradaki TTL'dir; asıl kuyruk ve neden son ölümdedir (B-37).
     */
    private static String lastOrFirst(MessageProperties props, String field) {
        String last = headerAsString(props, "x-last-death-" + field);
        return last != null ? last : headerAsString(props, "x-first-death-" + field);
    }

    private static String headerAsString(MessageProperties props, String name) {
        Object value = props.getHeader(name);
        return value == null ? null : value.toString();
    }
}
