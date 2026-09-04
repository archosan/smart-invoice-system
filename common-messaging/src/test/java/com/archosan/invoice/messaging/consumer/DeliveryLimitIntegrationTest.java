package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.MessagingIntegrationTest;
import com.archosan.invoice.messaging.SampleMessages;
import com.archosan.invoice.messaging.inbox.Inbox;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.outbox.OutboxRelay;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * definitions.json'daki gerçek kuyruk ve DLQ ile ADR-19'un kanıtı: dinleyicinin {@code basic.reject}'i quorum
 * kuyruğun teslim sayacını artırır, limit dolunca mesaj DLQ'ya düşer; zehirli mesaj doğrudan DLQ'ya gider.
 * Mesajlar outbox + relay ile yayınlanır, yani yol uçtan uca gerçektir.
 */
class DeliveryLimitIntegrationTest extends MessagingIntegrationTest {

    private static final String QUEUE = "extraction.extract-invoice.q";
    private static final String DLQ = "extraction.extract-invoice.dlq";
    private static final long TIMEOUT_MS = 15_000;

    @Autowired
    private InvoiceMessageListenerFactory listeners;
    @Autowired
    private OutboxWriter outbox;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private Inbox inbox;
    @Autowired
    private ConnectionFactory connectionFactory;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private SimpleMessageListenerContainer container;

    @BeforeEach
    void setUp() {
        amqpAdmin.purgeQueue(QUEUE, false);
        amqpAdmin.purgeQueue(DLQ, false);
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM inbox").update();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void failingHandlerExhaustsDeliveryLimitAndMessageIsDeadLettered() {
        AtomicInteger deliveries = new AtomicInteger();
        listen(listeners.forQueue(QUEUE)
                .on(ExtractInvoice.class, (envelope, message) -> {
                    deliveries.incrementAndGet();
                    throw new IllegalStateException("her denemede hata");
                })
                .build());
        UUID messageId = publish();

        Message deadLettered = rabbitTemplate.receive(DLQ, TIMEOUT_MS);

        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-first-death-reason"))
                .isEqualTo("delivery_limit");
        // x-delivery-limit = 3: üç başarısız teslime izin verilir, dördüncü reject'te mesaj DLQ'ya düşer.
        assertThat(deliveries).hasValue(4);
        assertThat(inbox.exists(messageId, QUEUE)).isFalse();
    }

    @Test
    void permanentFailureGoesToDeadLetterQueueWithoutRetry() {
        AtomicInteger deliveries = new AtomicInteger();
        listen(listeners.forQueue(QUEUE)
                .on(ExtractInvoice.class, (envelope, message) -> {
                    deliveries.incrementAndGet();
                    throw new AmqpRejectAndDontRequeueException("kalıcı hata");
                })
                .build());
        publish();

        Message deadLettered = rabbitTemplate.receive(DLQ, TIMEOUT_MS);

        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-first-death-reason"))
                .isEqualTo("rejected");
        assertThat(deliveries).hasValue(1);
    }

    @Test
    void poisonMessageGoesToDeadLetterQueueWithoutCallingHandler() {
        AtomicInteger deliveries = new AtomicInteger();
        listen(listeners.forQueue(QUEUE)
                .on(ExtractInvoice.class, (envelope, message) -> deliveries.incrementAndGet())
                .build());
        // documentId'siz gövde: dönüştürücü çözemez.
        jdbc.sql("""
                        INSERT INTO outbox (id, aggregate_id, message_type, exchange, routing_key, payload)
                        VALUES (:id, :aggregateId, 'ExtractInvoice', 'invoice.commands', 'extract.invoice', '{}')
                        """)
                .param("id", UUID.randomUUID())
                .param("aggregateId", SampleMessages.DOCUMENT_ID)
                .update();
        relay.relayBatch();

        Message deadLettered = rabbitTemplate.receive(DLQ, TIMEOUT_MS);

        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-first-death-reason"))
                .isEqualTo("rejected");
        assertThat(deliveries).hasValue(0);
    }

    @Test
    void successfulHandlerAcksAndNothingReachesDeadLetterQueue() throws InterruptedException {
        AtomicInteger deliveries = new AtomicInteger();
        listen(listeners.forQueue(QUEUE)
                .on(ExtractInvoice.class, (envelope, message) -> deliveries.incrementAndGet())
                .build());
        UUID messageId = publish();

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!inbox.exists(messageId, QUEUE) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(inbox.exists(messageId, QUEUE)).isTrue();
        assertThat(deliveries).hasValue(1);
        assertThat(rabbitTemplate.receive(DLQ, 500)).isNull();
    }

    @Test
    void applicationUserCannotDeclareTopology() {
        // ADR-17: topoloji yalnızca definitions.json'da; uygulama kullanıcısının configure izni yok.
        assertThatThrownBy(() -> amqpAdmin.declareQueue(new Queue("rogue.q")))
                .isInstanceOf(AmqpException.class);
    }

    private UUID publish() {
        UUID messageId = new TransactionTemplate(transactionManager).execute(status ->
                outbox.add(SampleMessages.DOCUMENT_ID, SampleMessages.extractInvoice()));
        assertThat(relay.relayBatch().published()).isEqualTo(1);
        return messageId;
    }

    private void listen(InvoiceMessageListener listener) {
        container = InvoiceListenerContainers.create(connectionFactory, listener, 1, 1);
        container.start();
    }
}
