package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.SampleMessages;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** DLQ dinleyicisi hiçbir mesajı düşürmez: {@code basicReject(requeue=false)} hiçbir yolda gönderilmez. */
class DeadLetterListenerTest {

    private static final String DLQ = "extraction.extract-invoice.dlq";
    private static final long TAG = 9L;
    private static final UUID MESSAGE_ID = UUID.randomUUID();

    private final InvoiceMessageConverter converter = new InvoiceMessageConverter();
    private final Inbox inbox = mock(Inbox.class);
    private final Channel channel = mock(Channel.class);
    private final List<DeadLetter> handled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(inbox.tryInsert(MESSAGE_ID, DLQ)).thenReturn(true);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acksAfterHandlingConvertibleMessageWithDeathInfo() throws Exception {
        listener(handled::add).onMessage(deadLettered(validMessage()), channel);

        verify(channel).basicAck(TAG, false);
        verifyNoMoreInteractions(channel);
        assertThat(handled).singleElement().satisfies(dl -> {
            assertThat(dl.deadLetterQueue()).isEqualTo(DLQ);
            assertThat(dl.payload()).isEqualTo(SampleMessages.extractInvoice());
            assertThat(dl.envelope().messageId()).isEqualTo(MESSAGE_ID);
            assertThat(dl.deathQueue()).isEqualTo("extraction.extract-invoice.q");
            assertThat(dl.deathReason()).isEqualTo("delivery_limit");
            assertThat(dl.xDeath()).singleElement().satisfies(death -> assertThat(death.get("queue"))
                    .isEqualTo("extraction.extract-invoice.q"));
        });
    }

    @Test
    void unconvertibleMessageIsHandedOverRawNotDropped() throws Exception {
        Message message = validMessage();
        Message poison = deadLettered(new Message("bozuk {".getBytes(StandardCharsets.UTF_8),
                message.getMessageProperties()));

        listener(handled::add).onMessage(poison, channel);

        verify(channel).basicAck(TAG, false);
        assertThat(handled).singleElement().satisfies(dl -> {
            assertThat(dl.payload()).isNull();
            assertThat(dl.envelope()).isNull();
            assertThat(dl.bodyAsText()).isEqualTo("bozuk {");
            assertThat(dl.type()).isEqualTo("ExtractInvoice");
            assertThat(dl.messageIdAsUuid()).contains(MESSAGE_ID);
        });
    }

    @Test
    void handlerFailureRequeuesInsteadOfDropping() throws Exception {
        listener(dl -> {
            throw new IllegalStateException("veritabanı yok");
        }).onMessage(deadLettered(validMessage()), channel);

        verify(channel).basicReject(TAG, true);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void evenRejectAndDontRequeueFromHandlerRequeues() throws Exception {
        listener(dl -> {
            throw new AmqpRejectAndDontRequeueException("kalıcı");
        }).onMessage(deadLettered(validMessage()), channel);

        verify(channel).basicReject(TAG, true);
        verify(channel, never()).basicReject(anyLong(), eq(false));
    }

    @Test
    void duplicateIsAckedWithoutHandling() throws Exception {
        when(inbox.tryInsert(MESSAGE_ID, DLQ)).thenReturn(false);

        listener(handled::add).onMessage(deadLettered(validMessage()), channel);

        verify(channel).basicAck(TAG, false);
        assertThat(handled).isEmpty();
    }

    @Test
    void messageWithoutMessageIdIsHandledWithoutInbox() throws Exception {
        Message message = deadLettered(validMessage());
        message.getMessageProperties().setMessageId(null);

        listener(handled::add).onMessage(message, channel);

        verify(channel).basicAck(TAG, false);
        assertThat(handled).hasSize(1);
        verify(inbox, never()).tryInsert(any(), any());
    }

    @Test
    void handlerSeesMessageIdsInMdcAndMdcIsRestored() throws Exception {
        List<Map<String, String>> seen = new ArrayList<>();

        listener(dl -> seen.add(MDC.getCopyOfContextMap())).onMessage(deadLettered(validMessage()), channel);

        assertThat(seen).singleElement().satisfies(mdc -> assertThat(mdc)
                .containsEntry("messageId", MESSAGE_ID.toString())
                .containsEntry("correlationId", SampleMessages.DOCUMENT_ID.toString()));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void ackFailureIsNotSwallowedAsReject() throws Exception {
        doThrow(new IOException("kanal kapandı")).when(channel).basicAck(TAG, false);

        assertThatThrownBy(() -> listener(handled::add).onMessage(deadLettered(validMessage()), channel))
                .isInstanceOf(IOException.class);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    private DeadLetterListener listener(DeadLetterHandler handler) {
        return new DeadLetterListener(DLQ, converter, inbox, mock(PlatformTransactionManager.class), handler);
    }

    private Message validMessage() {
        return converter.toAmqpMessage(
                MessageEnvelope.of(MESSAGE_ID, SampleMessages.DOCUMENT_ID, MessageType.EXTRACT_INVOICE),
                SampleMessages.extractInvoice());
    }

    /**
     * B-37: bekleme odasından geçip sonra reddedilen mesajın ilk ölümü bekleme odasındaki TTL'dir; DLQ'ya düşüren
     * son ölümdür (asıl kuyruk, {@code rejected}).
     */
    @Test
    void deathIsTheLastOneWhenMessageWentThroughWaitingRoom() throws Exception {
        Message message = deadLettered(validMessage());
        message.getMessageProperties().setHeader("x-first-death-queue", "rpa.post-to-portal.wait-30s");
        message.getMessageProperties().setHeader("x-first-death-reason", "expired");
        message.getMessageProperties().setHeader("x-last-death-queue", "rpa.post-to-portal.q");
        message.getMessageProperties().setHeader("x-last-death-reason", "rejected");

        listener(handled::add).onMessage(message, channel);

        assertThat(handled).singleElement().satisfies(dl -> {
            assertThat(dl.deathQueue()).isEqualTo("rpa.post-to-portal.q");
            assertThat(dl.deathReason()).isEqualTo("rejected");
        });
    }

    private static Message deadLettered(Message message) {
        MessageProperties props = message.getMessageProperties();
        props.setDeliveryTag(TAG);
        props.setHeader("x-first-death-queue", "extraction.extract-invoice.q");
        props.setHeader("x-first-death-reason", "delivery_limit");
        props.setHeader("x-death", List.of(Map.of("queue", "extraction.extract-invoice.q", "reason", "delivery_limit",
                "count", 1L)));
        return message;
    }
}
