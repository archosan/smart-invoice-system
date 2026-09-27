package com.archosan.invoice.rpa.pipeline;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Redis yoksa fail-closed (B-34, DECISIONS.md §4.4): kilit alınamamış sayılır, portala dokunulmaz, mesaj bekleme
 * odasına gider ve teslim limiti tüketilmez. {@link RedissonClient} kapalı bir porta bağlı, servisteki gibi tembel
 * başlatılan bir istemciyle değiştirilir (taban sınıfın Redis ayarı {@code @DynamicPropertySource} ile önce
 * kaydedildiği için ayarla ezilemez).
 */
@Import(RedisDownFailClosedIntegrationTest.UnreachableRedis.class)
class RedisDownFailClosedIntegrationTest extends RpaIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class UnreachableRedis {

        @Bean(destroyMethod = "shutdown")
        @Primary
        RedissonClient unreachableRedisson() {
            Config config = new Config();
            config.setLazyInitialization(true);
            config.useSingleServer().setAddress("redis://localhost:" + closedPort())
                    .setConnectTimeout(500).setRetryAttempts(1).setTimeout(1000);
            return Redisson.create(config);
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void withoutRedisInvoiceIsNotEnteredAndWaitsInWaitingRoom() {
        UUID documentId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        String invoiceNo = "NOREDIS-" + UUID.randomUUID();

        rabbitTemplate.send("invoice.commands", "rpa.post", converter.toAmqpMessage(
                MessageEnvelope.of(messageId, documentId, MessageType.POST_TO_PORTAL),
                new PostToPortal(documentId, "4810293756", "Anadolu Rulman A.Ş.", invoiceNo, LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 10, 1), new BigDecimal("5100.00"), new BigDecimal("850.00"), "TRY")));

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM outbox
                        WHERE id = :id AND exchange = 'invoice.retry' AND routing_key = 'rpa.post.wait'
                        """).param("id", messageId).query(Long.class).single() == 1);
        assertThat(jdbc.sql("SELECT count(*) FROM inbox WHERE message_id = :id").param("id", messageId)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM portal_submissions WHERE document_id = :id")
                .param("id", documentId).query(Long.class).single()).isZero();
        assertThat(MockPortal.invoices(invoiceNo)).isEmpty();
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
