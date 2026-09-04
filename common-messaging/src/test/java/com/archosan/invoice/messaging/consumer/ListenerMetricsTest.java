package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.SampleMessages;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B-48: her mesajın sonucu ve süresi kaydedilir; ack/reject kararı değişmez. */
class ListenerMetricsTest {

    private static final String QUEUE = "extraction.extract-invoice.q";
    private static final String DLQ = "extraction.extract-invoice.dlq";
    private static final long TAG = 7L;
    private static final UUID MESSAGE_ID = UUID.randomUUID();

    private final InvoiceMessageConverter converter = new InvoiceMessageConverter();
    private final Inbox inbox = mock(Inbox.class);
    private final Channel channel = mock(Channel.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InvoiceMessageListenerFactory factory = new InvoiceMessageListenerFactory(converter, inbox,
            mock(PlatformTransactionManager.class), new MessagingMetrics(registry));

    @BeforeEach
    void setUp() {
        when(inbox.tryInsert(MESSAGE_ID, QUEUE)).thenReturn(true);
        when(inbox.tryInsert(MESSAGE_ID, DLQ)).thenReturn(true);
    }

    @Test
    void recordsEachOutcomeWithQueueAndType() throws Exception {
        factory.forQueue(QUEUE).on(ExtractInvoice.class, (e, m) -> { }).build().onMessage(message(), channel);
        factory.forQueue(QUEUE).on(ExtractInvoice.class, (e, m) -> {
            throw new IllegalStateException("geçici");
        }).build().onMessage(message(), channel);
        factory.forQueue(QUEUE).on(ExtractInvoice.class, (e, m) -> {
            throw new AmqpRejectAndDontRequeueException("kalıcı");
        }).build().onMessage(message(), channel);
        factory.forQueue(QUEUE).onLongRunning(ExtractInvoice.class, (e, m, c) -> c.defer(() -> { })).build()
                .onMessage(message(), channel);
        when(inbox.tryInsert(MESSAGE_ID, QUEUE)).thenReturn(false);
        factory.forQueue(QUEUE).on(ExtractInvoice.class, (e, m) -> { }).build().onMessage(message(), channel);

        assertThat(counts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ack", 1L, "requeue", 1L, "dead_letter", 1L, "deferred", 1L, "duplicate", 1L));
        Timer ack = registry.get("invoice.messages.processed").tags("queue", QUEUE, "type", "ExtractInvoice",
                "outcome", "ack").timer();
        assertThat(ack.count()).isEqualTo(1);
    }

    @Test
    void poisonMessageIsRecordedWithUnknownType() throws Exception {
        Message poison = new Message("bozuk".getBytes(StandardCharsets.UTF_8));
        poison.getMessageProperties().setDeliveryTag(TAG);

        factory.forQueue(QUEUE).on(ExtractInvoice.class, (e, m) -> { }).build().onMessage(poison, channel);

        verify(channel).basicReject(TAG, false);
        assertThat(registry.get("invoice.messages.processed").tags("type", "unknown", "outcome", "dead_letter")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void newDeadLettersAreCountedOnce() throws Exception {
        DeadLetterListener listener = factory.forDeadLetterQueue(DLQ, deadLetter -> { });

        listener.onMessage(message(), channel);
        when(inbox.tryInsert(MESSAGE_ID, DLQ)).thenReturn(false);
        listener.onMessage(message(), channel);

        assertThat(registry.get("invoice.dead.letters.received").tags("queue", DLQ, "type", "ExtractInvoice")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void noRegistryMeansNoOp() throws Exception {
        new InvoiceMessageListenerFactory(converter, inbox, mock(PlatformTransactionManager.class))
                .forQueue(QUEUE).on(ExtractInvoice.class, (e, m) -> { }).build().onMessage(message(), channel);

        verify(channel).basicAck(TAG, false);
    }

    private Map<String, Long> counts() {
        return registry.find("invoice.messages.processed").timers().stream()
                .collect(Collectors.toMap(t -> t.getId().getTag("outcome"), Timer::count));
    }

    private Message message() {
        Message message = converter.toAmqpMessage(
                MessageEnvelope.of(MESSAGE_ID, SampleMessages.DOCUMENT_ID, MessageType.EXTRACT_INVOICE),
                SampleMessages.extractInvoice());
        message.getMessageProperties().setDeliveryTag(TAG);
        return message;
    }
}
