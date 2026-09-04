package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.Exchanges;
import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.MessagingIntegrationTest;
import com.archosan.invoice.messaging.message.PostToPortal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gerçek topoloji (definitions.json, B-34): {@code invoice.retry} · {@code rpa.post.wait} ile bekleme odasına giren
 * mesaj TTL (30 sn) dolunca DLX ile {@code invoice.commands} · {@code rpa.post} üzerinden asıl kuyruğa <b>aynı
 * {@code message_id}</b> ile döner; teslim limitini tüketmez.
 */
class WaitingRoomIntegrationTest extends MessagingIntegrationTest {

    private static final String QUEUE = "rpa.post-to-portal.q";
    private static final String WAITING_ROOM = "rpa.post-to-portal.wait-30s";

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private InvoiceMessageConverter converter;

    @BeforeEach
    void emptyQueues() {
        amqpAdmin.purgeQueue(QUEUE, false);
        amqpAdmin.purgeQueue(WAITING_ROOM, false);
    }

    @Test
    void messageReturnsToItsQueueWithSameIdAfterTtl() {
        UUID messageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Message message = converter.toAmqpMessage(MessageEnvelope.of(messageId, documentId, MessageType.POST_TO_PORTAL),
                new PostToPortal(documentId, "4810293756", "ACME", "F-1", LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 1), new BigDecimal("12.00"), new BigDecimal("2.00"), "TRY"));
        long start = System.nanoTime();

        rabbitTemplate.send(Exchanges.RETRY, "rpa.post.wait", message);

        // Beklemedeyken asıl kuyrukta yok.
        assertThat(rabbitTemplate.receive(QUEUE, 5_000)).isNull();
        Message returned = rabbitTemplate.receive(QUEUE, 45_000);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(returned).isNotNull();
        assertThat(returned.getMessageProperties().getMessageId()).isEqualTo(messageId.toString());
        assertThat(returned.getMessageProperties().getCorrelationId()).isEqualTo(documentId.toString());
        assertThat(returned.getMessageProperties().<String>getHeader("x-first-death-reason")).isEqualTo("expired");
        assertThat(waited).isBetween(Duration.ofSeconds(28), Duration.ofSeconds(45));
    }
}
