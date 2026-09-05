package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.SampleMessages;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.archosan.invoice.messaging.message.RpaCompleted;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Ack / reject kararları; inbox ve transaction mock'tur. Gerçek inbox davranışı InboxIntegrationTest'te. */
class InvoiceMessageListenerTest {

    private static final String QUEUE = "extraction.extract-invoice.q";
    private static final long DELIVERY_TAG = 42L;
    private static final UUID MESSAGE_ID = UUID.randomUUID();

    private final InvoiceMessageConverter converter = new InvoiceMessageConverter();
    private final Inbox inbox = mock(Inbox.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Channel channel = mock(Channel.class);

    @BeforeEach
    void setUp() {
        when(inbox.tryInsert(MESSAGE_ID, QUEUE)).thenReturn(true);
        when(inbox.exists(MESSAGE_ID, QUEUE)).thenReturn(false);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void handlerSeesCorrelationInMdcAndMdcIsClearedAfterwards() throws Exception {
        List<Map<String, String>> seen = new ArrayList<>();
        InvoiceMessageListener listener = listener((envelope, message) -> seen.add(MDC.getCopyOfContextMap()));

        listener.onMessage(extractInvoiceMessage(), channel);

        assertThat(seen).singleElement().satisfies(mdc -> assertThat(mdc)
                .containsEntry("correlationId", SampleMessages.DOCUMENT_ID.toString())
                .containsEntry("messageId", MESSAGE_ID.toString())
                .containsEntry("messageType", "ExtractInvoice"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void mdcIsClearedAfterFailingHandler() throws Exception {
        InvoiceMessageListener listener = listener((envelope, message) -> {
            throw new IllegalStateException("hata");
        });

        listener.onMessage(extractInvoiceMessage(), channel);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void poisonMessageRestoresCallersMdcAfterwards() throws Exception {
        InvoiceMessageListener listener = listener((envelope, message) -> { });
        Message message = extractInvoiceMessage();
        Message poison = new Message("{bozuk".getBytes(StandardCharsets.UTF_8), message.getMessageProperties());
        Map<String, String> outer = Map.of("correlationId", "dış");
        MDC.setContextMap(outer);

        listener.onMessage(poison, channel);

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(outer);
    }

    @Test
    void acksWhenHandlerReturns() throws Exception {
        List<MessageEnvelope> envelopes = new ArrayList<>();
        List<ExtractInvoice> payloads = new ArrayList<>();
        InvoiceMessageListener listener = listener((envelope, message) -> {
            envelopes.add(envelope);
            payloads.add(message);
        });

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoMoreInteractions(channel);
        assertThat(envelopes).singleElement().extracting(MessageEnvelope::messageId).isEqualTo(MESSAGE_ID);
        assertThat(payloads).containsExactly(SampleMessages.extractInvoice());
        verify(inbox).tryInsert(MESSAGE_ID, QUEUE);
    }

    @Test
    void acksDuplicateWithoutCallingHandler() throws Exception {
        when(inbox.tryInsert(MESSAGE_ID, QUEUE)).thenReturn(false);
        List<InvoiceMessage> handled = new ArrayList<>();
        InvoiceMessageListener listener = listener((envelope, message) -> handled.add(message));

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verifyNoMoreInteractions(channel);
        assertThat(handled).isEmpty();
    }

    @Test
    void requeuesWithRejectWhenHandlerThrows() throws Exception {
        InvoiceMessageListener listener = listener((envelope, message) -> {
            throw new IllegalStateException("veritabanı geçici olarak yok");
        });

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicReject(DELIVERY_TAG, true);
        verifyNoMoreInteractions(channel);
        verify(transactionManager).rollback(any());
    }

    @Test
    void requeuesWhenInboxFails() throws Exception {
        when(inbox.tryInsert(MESSAGE_ID, QUEUE)).thenThrow(new IllegalStateException("bağlantı yok"));
        InvoiceMessageListener listener = listener((envelope, message) -> { });

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicReject(DELIVERY_TAG, true);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void deadLettersWhenHandlerSignalsPermanentFailure() throws Exception {
        InvoiceMessageListener listener = listener((envelope, message) -> {
            throw new AmqpRejectAndDontRequeueException("kalıcı hata");
        });

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicReject(DELIVERY_TAG, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void deadLettersWhenPermanentFailureIsWrapped() throws Exception {
        InvoiceMessageListener listener = listener((envelope, message) -> {
            throw new RuntimeException(new AmqpRejectAndDontRequeueException("kalıcı hata"));
        });

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicReject(DELIVERY_TAG, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void deadLettersPoisonMessageWithoutCallingHandler() throws Exception {
        List<InvoiceMessage> handled = new ArrayList<>();
        InvoiceMessageListener listener = listener((envelope, message) -> handled.add(message));
        Message message = extractInvoiceMessage();
        Message poison = new Message("{bozuk".getBytes(StandardCharsets.UTF_8), message.getMessageProperties());

        listener.onMessage(poison, channel);

        verify(channel).basicReject(DELIVERY_TAG, false);
        verifyNoMoreInteractions(channel);
        assertThat(handled).isEmpty();
        verify(inbox, never()).tryInsert(any(), any());
    }

    @Test
    void deadLettersTypeWithoutHandlerOnThisQueue() throws Exception {
        InvoiceMessageListener listener = listener((envelope, message) -> { });
        MessageEnvelope envelope = MessageEnvelope.of(MESSAGE_ID, SampleMessages.DOCUMENT_ID,
                MessageType.RPA_COMPLETED);
        Message message = converter.toAmqpMessage(envelope, new RpaCompleted(SampleMessages.DOCUMENT_ID, "1", false));
        message.getMessageProperties().setDeliveryTag(DELIVERY_TAG);

        listener.onMessage(message, channel);

        verify(channel).basicReject(DELIVERY_TAG, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void longRunningSkipsAlreadyProcessedMessageBeforeWork() throws Exception {
        when(inbox.exists(MESSAGE_ID, QUEUE)).thenReturn(true);
        List<InvoiceMessage> handled = new ArrayList<>();
        InvoiceMessageListener listener = longRunningListener((envelope, message, completion) -> handled.add(message));

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(handled).isEmpty();
        verify(inbox, never()).tryInsert(any(), any());
    }

    @Test
    void longRunningWritesResultThroughCompletion() throws Exception {
        List<String> written = new ArrayList<>();
        List<Boolean> applied = new ArrayList<>();
        InvoiceMessageListener listener = longRunningListener((envelope, message, completion) ->
                applied.add(completion.complete(() -> written.add("sonuç"))));

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(written).containsExactly("sonuç");
        assertThat(applied).containsExactly(true);
    }

    @Test
    void longRunningDiscardsResultWhenAnotherCopyWon() throws Exception {
        when(inbox.tryInsert(MESSAGE_ID, QUEUE)).thenReturn(false);
        List<String> written = new ArrayList<>();
        List<Boolean> applied = new ArrayList<>();
        InvoiceMessageListener listener = longRunningListener((envelope, message, completion) ->
                applied.add(completion.complete(() -> written.add("sonuç"))));

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(written).isEmpty();
        assertThat(applied).containsExactly(false);
    }

    @Test
    void longRunningRequeuesWhenHandlerForgetsToComplete() throws Exception {
        InvoiceMessageListener listener = longRunningListener((envelope, message, completion) -> { });

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicReject(DELIVERY_TAG, true);
        verifyNoMoreInteractions(channel);
        verify(inbox, never()).tryInsert(any(), any());
    }

    @Test
    void longRunningRequeuesWhenCompletionIsCalledTwice() throws Exception {
        InvoiceMessageListener listener = longRunningListener((envelope, message, completion) -> {
            completion.complete(() -> { });
            completion.complete(() -> { });
        });

        listener.onMessage(extractInvoiceMessage(), channel);

        verify(channel).basicReject(DELIVERY_TAG, true);
    }

    @Test
    void refusesSecondHandlerForSameType() {
        InvoiceMessageListener.Builder builder = builder().on(ExtractInvoice.class, (envelope, message) -> { });

        assertThatThrownBy(() -> builder.onLongRunning(ExtractInvoice.class, (envelope, message, completion) -> { }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesListenerWithoutHandlers() {
        assertThatThrownBy(() -> builder().build()).isInstanceOf(IllegalStateException.class);
    }

    private InvoiceMessageListener.Builder builder() {
        return InvoiceMessageListener.builder(QUEUE, converter, inbox, transactionManager);
    }

    private InvoiceMessageListener listener(MessageHandler<ExtractInvoice> handler) {
        return builder().on(ExtractInvoice.class, handler).build();
    }

    private InvoiceMessageListener longRunningListener(LongRunningMessageHandler<ExtractInvoice> handler) {
        return builder().onLongRunning(ExtractInvoice.class, handler).build();
    }

    private Message extractInvoiceMessage() {
        MessageEnvelope envelope = MessageEnvelope.of(MESSAGE_ID, SampleMessages.DOCUMENT_ID,
                MessageType.EXTRACT_INVOICE);
        Message message = converter.toAmqpMessage(envelope, SampleMessages.extractInvoice());
        message.getMessageProperties().setDeliveryTag(DELIVERY_TAG);
        return message;
    }
}
