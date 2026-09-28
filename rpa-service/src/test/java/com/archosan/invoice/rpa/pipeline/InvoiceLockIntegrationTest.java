package com.archosan.invoice.rpa.pipeline;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Fatura kilidi ve bekleme odası (B-34, FR-R7): kilit başka bir sahipteyken mesaj portala dokunmadan aynı kimlikle
 * bekleme odasına gider, inbox'a yazılmaz; kilit bırakılınca 30 sn sonra geri gelir ve işlenir. Gerçek Redis, gerçek
 * broker topolojisi, gerçek portal.
 */
class InvoiceLockIntegrationTest extends RpaIntegrationTest {

    private static final Duration RETURN_TIMEOUT = Duration.ofSeconds(90);

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RedissonClient redisson;

    @Test
    void invoiceLockedElsewhereWaitsInWaitingRoomThenIsEnteredOnce() {
        UUID documentId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String invoiceNo = "LCK-" + UUID.randomUUID();
        // Başka bir örnek bu faturayı giriyor: kilit bu testin thread'inde.
        RLock other = redisson.getLock("lock:rpa:4810293756:" + invoiceNo);
        other.lock();
        try {
            send(messageId, documentId, invoiceNo);

            await().atMost(Duration.ofSeconds(20)).until(() -> outboxTarget(messageId) != null);
            assertThat(outboxTarget(messageId)).isEqualTo("invoice.retry rpa.post.wait");
            assertThat(inboxRows(messageId)).isZero();
            assertThat(MockPortal.invoices(invoiceNo)).isEmpty();
            assertThat(submissionCount(documentId)).isZero();
        } finally {
            other.unlock();
        }

        // Bekleme odasından aynı kimlikle döner, bu kez kilit boş: girilir.
        await().atMost(RETURN_TIMEOUT).until(() -> rpaCompleted(documentId) == 1);
        assertThat(MockPortal.invoices(invoiceNo)).hasSize(1);
        assertThat(inboxRows(messageId)).isEqualTo(1);
        // İş bitince kilit bırakıldı.
        assertThat(redisson.getLock("lock:rpa:4810293756:" + invoiceNo).isLocked()).isFalse();
    }

    @Test
    void lockIsReleasedAfterSuccessfulEntry() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = "REL-" + UUID.randomUUID();

        send(UUID.randomUUID(), documentId, invoiceNo);

        await().atMost(Duration.ofSeconds(60)).until(() -> rpaCompleted(documentId) == 1);
        assertThat(redisson.getLock("lock:rpa:4810293756:" + invoiceNo).isLocked()).isFalse();
    }

    private void send(UUID messageId, UUID documentId, String invoiceNo) {
        rabbitTemplate.send("invoice.commands", "rpa.post", converter.toAmqpMessage(
                MessageEnvelope.of(messageId, documentId, MessageType.POST_TO_PORTAL),
                new PostToPortal(documentId, "4810293756", "Anadolu Rulman A.Ş.", invoiceNo, LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 1), new BigDecimal("5100.00"), new BigDecimal("850.00"), "TRY")));
    }

    private String outboxTarget(UUID messageId) {
        return jdbc.sql("SELECT exchange || ' ' || routing_key FROM outbox WHERE id = :id")
                .param("id", messageId).query(String.class).optional().orElse(null);
    }

    private long inboxRows(UUID messageId) {
        return jdbc.sql("SELECT count(*) FROM inbox WHERE message_id = :id").param("id", messageId)
                .query(Long.class).single();
    }

    private long submissionCount(UUID documentId) {
        return jdbc.sql("SELECT count(*) FROM portal_submissions WHERE document_id = :id").param("id", documentId)
                .query(Long.class).single();
    }

    private long rpaCompleted(UUID documentId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND message_type = 'RpaCompleted'")
                .param("id", documentId).query(Long.class).single();
    }
}
