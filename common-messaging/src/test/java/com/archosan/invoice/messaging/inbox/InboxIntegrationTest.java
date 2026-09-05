package com.archosan.invoice.messaging.inbox;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.SampleMessages;
import com.archosan.invoice.messaging.MessagingIntegrationTest;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListener;
import com.archosan.invoice.messaging.consumer.InvoiceMessageListenerFactory;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class InboxIntegrationTest extends MessagingIntegrationTest {

    private static final String QUEUE = "extraction.extract-invoice.q";
    private static final long DELIVERY_TAG = 7L;

    @Autowired
    private Inbox inbox;
    @Autowired
    private InboxCleaner cleaner;
    @Autowired
    private InboxProperties properties;
    @Autowired
    private InvoiceMessageListenerFactory listeners;
    @Autowired
    private OutboxWriter outbox;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.sql("DELETE FROM inbox").update();
        jdbc.sql("DELETE FROM outbox").update();
    }

    @Test
    void tryInsertRecordsMessageOnlyOncePerConsumer() {
        UUID id = UUID.randomUUID();

        assertThat(insertInTransaction(id, QUEUE)).isTrue();
        assertThat(insertInTransaction(id, QUEUE)).isFalse();
        assertThat(insertInTransaction(id, "another.q")).isTrue();
        assertThat(inbox.exists(id, QUEUE)).isTrue();
        assertThat(inbox.exists(UUID.randomUUID(), QUEUE)).isFalse();
    }

    @Test
    void tryInsertRefusesToRunOutsideTransaction() {
        assertThatThrownBy(() -> inbox.tryInsert(UUID.randomUUID(), QUEUE))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rolledBackInsertLeavesNoRecord() {
        UUID id = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            inbox.tryInsert(id, QUEUE);
            status.setRollbackOnly();
        });

        assertThat(inbox.exists(id, QUEUE)).isFalse();
    }

    @Test
    void concurrentInsertWaitsForFirstCommitAndLoses() throws Exception {
        UUID id = UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            boolean result = inbox.tryInsert(id, QUEUE);
            inserted.countDown();
            await(commit);
            return result;
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> tx.execute(s -> inbox.tryInsert(id, QUEUE)));
        Thread.sleep(300);
        assertThat(second).isNotDone();
        commit.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void cleanerDeletesOnlyExpiredRowsInChunks() {
        for (int i = 0; i < 5; i++) {
            insertWithAge(UUID.randomUUID(), Duration.ofDays(31));
        }
        UUID recent = UUID.randomUUID();
        insertWithAge(recent, Duration.ofDays(29));
        InboxCleaner smallChunks = new InboxCleaner(inbox,
                new InboxProperties(Duration.ofDays(30), Duration.ofHours(1), 2, properties.cleanup()));

        assertThat(smallChunks.cleanUp()).isEqualTo(5);
        assertThat(count()).isEqualTo(1);
        assertThat(inbox.exists(recent, QUEUE)).isTrue();
        assertThat(cleaner.cleanUp()).isZero();
    }

    @Test
    void listenerProcessesRedeliveredMessageOnlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InvoiceMessageListener listener = listeners.forQueue(QUEUE)
                .on(ExtractInvoice.class, (envelope, message) -> {
                    calls.incrementAndGet();
                    outbox.add(message.documentId(), new ExtractInvoice(message.documentId(), "x", "y"));
                })
                .build();
        Channel channel = mock(Channel.class);
        Message message = message(UUID.randomUUID());

        listener.onMessage(message, channel);
        listener.onMessage(message, channel);

        assertThat(calls).hasValue(1);
        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void failedHandlerRollsBackInboxAndOutboxSoRedeliveryIsProcessed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InvoiceMessageListener listener = listeners.forQueue(QUEUE)
                .on(ExtractInvoice.class, (envelope, message) -> {
                    outbox.add(message.documentId(), message);
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("ilk denemede hata");
                    }
                })
                .build();
        Channel channel = mock(Channel.class);
        UUID messageId = UUID.randomUUID();

        listener.onMessage(message(messageId), channel);
        assertThat(inbox.exists(messageId, QUEUE)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();

        listener.onMessage(message(messageId), channel);

        verify(channel).basicReject(DELIVERY_TAG, true);
        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(calls).hasValue(2);
        assertThat(inbox.exists(messageId, QUEUE)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void longRunningResultIsDiscardedWhenAnotherCopyCommittedFirst() throws Exception {
        UUID messageId = UUID.randomUUID();
        AtomicInteger written = new AtomicInteger();
        InvoiceMessageListener listener = listeners.forQueue(QUEUE)
                .onLongRunning(ExtractInvoice.class, (envelope, message, completion) -> {
                    // Uzun iş sürerken başka bir kopya işi bitirip commit etmiş olsun.
                    tx.executeWithoutResult(s -> inbox.tryInsert(messageId, QUEUE));
                    completion.complete(() -> {
                        written.incrementAndGet();
                        outbox.add(message.documentId(), message);
                    });
                })
                .build();
        Channel channel = mock(Channel.class);

        listener.onMessage(message(messageId), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(written).hasValue(0);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
    }

    @Test
    void longRunningWritesResultAndInboxTogether() throws Exception {
        UUID messageId = UUID.randomUUID();
        InvoiceMessageListener listener = listeners.forQueue(QUEUE)
                .onLongRunning(ExtractInvoice.class, (envelope, message, completion) ->
                        completion.complete(() -> outbox.add(message.documentId(), message)))
                .build();
        Channel channel = mock(Channel.class);

        listener.onMessage(message(messageId), channel);
        listener.onMessage(message(messageId), channel);

        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
        assertThat(inbox.exists(messageId, QUEUE)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isEqualTo(1);
    }

    /**
     * B-34: ertelenen mesaj aynı kimlikle bekleme odasına yazılır, inbox'a yazılmaz ve ack edilir; aynı kimlikle geri
     * geldiğinde inbox onu işlenmemiş bulur ve handler yeniden çağrılır.
     */
    @Test
    void deferredMessageIsRepublishedWithSameIdWithoutInboxAndProcessedWhenItReturns() throws Exception {
        UUID messageId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        InvoiceMessageListener listener = listeners.forQueue(QUEUE)
                .onLongRunning(ExtractInvoice.class, (envelope, message, completion) -> {
                    if (calls.incrementAndGet() == 1) {
                        completion.defer(() -> outbox.republish(envelope, message, "invoice.retry", "rpa.post.wait"));
                    } else {
                        completion.complete(() -> outbox.add(message.documentId(), message));
                    }
                })
                .build();
        Channel channel = mock(Channel.class);

        listener.onMessage(message(messageId), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        assertThat(inbox.exists(messageId, QUEUE)).isFalse();
        assertThat(jdbc.sql("""
                        SELECT exchange || ' ' || routing_key || ' ' || aggregate_id FROM outbox WHERE id = :id
                        """).param("id", messageId).query(String.class).single())
                .isEqualTo("invoice.retry rpa.post.wait " + SampleMessages.DOCUMENT_ID);

        listener.onMessage(message(messageId), channel);

        assertThat(calls).hasValue(2);
        assertThat(inbox.exists(messageId, QUEUE)).isTrue();
    }

    /** Aynı mesaj bekleme odasından dönüp yine ertelenirse tek satır kalır ve yeniden yayınlanmak üzere açılır. */
    @Test
    void deferringTheSameMessageAgainReArmsItsOutboxRow() throws Exception {
        UUID messageId = UUID.randomUUID();
        InvoiceMessageListener listener = listeners.forQueue(QUEUE)
                .onLongRunning(ExtractInvoice.class, (envelope, message, completion) -> completion.defer(
                        () -> outbox.republish(envelope, message, "invoice.retry", "rpa.post.wait")))
                .build();
        Channel channel = mock(Channel.class);

        listener.onMessage(message(messageId), channel);
        jdbc.sql("UPDATE outbox SET published_at = now(), attempts = 2 WHERE id = :id").param("id", messageId).update();
        listener.onMessage(message(messageId), channel);

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM outbox WHERE id = :id AND published_at IS NULL AND attempts = 0
                        """).param("id", messageId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isEqualTo(1);
        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void handlerMayNotBothDeferAndComplete() throws Exception {
        InvoiceMessageListener listener = listeners.forQueue(QUEUE)
                .onLongRunning(ExtractInvoice.class, (envelope, message, completion) -> {
                    completion.defer(() -> { });
                    completion.complete(() -> { });
                })
                .build();
        Channel channel = mock(Channel.class);

        listener.onMessage(message(UUID.randomUUID()), channel);

        // İkinci çağrı programlama hatasıdır: mesaj geri konur.
        verify(channel).basicReject(DELIVERY_TAG, true);
    }

    private boolean insertInTransaction(UUID id, String consumer) {
        return Boolean.TRUE.equals(tx.execute(status -> inbox.tryInsert(id, consumer)));
    }

    private Message message(UUID messageId) {
        MessageEnvelope envelope = MessageEnvelope.of(messageId, SampleMessages.DOCUMENT_ID,
                MessageType.EXTRACT_INVOICE);
        Message message = converter.toAmqpMessage(envelope, SampleMessages.extractInvoice());
        message.getMessageProperties().setDeliveryTag(DELIVERY_TAG);
        return message;
    }

    private void insertWithAge(UUID id, Duration age) {
        jdbc.sql("""
                        INSERT INTO inbox (message_id, consumer, processed_at)
                        VALUES (:id, :consumer, now() - make_interval(secs => CAST(:seconds AS double precision)))
                        """)
                .param("id", id)
                .param("consumer", QUEUE)
                .param("seconds", age.toSeconds())
                .update();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM inbox").query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
