package com.archosan.invoice.rpa.pipeline;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.message.RpaCompleted;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code PostToPortal} gerçek kuyruğa gönderilir; gerçek dinleyici headless Chromium ile JVM içindeki gerçek
 * mock-portal'a girer (FR-R1, FR-R2). Sonuç outbox'tan, {@code portal_submissions}'tan ve portalın kendi
 * kayıtlarından okunur.
 */
@TestPropertySource(properties = "invoice.rpa.max-attempts=2")
class PostToPortalFlowIntegrationTest extends RpaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** Bir bekleme odası turu (30 sn) ve iki tarayıcı denemesi. */
    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(100);
    private static final String DLQ = "rpa.post-to-portal.dlq";

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void emptyDlq() {
        amqpAdmin.purgeQueue(DLQ, false);
    }

    @Test
    void invoiceIsEnteredAndRefNoReported() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = "ANK-" + UUID.randomUUID();

        send(command(documentId, invoiceNo, "4810293756", new BigDecimal("5100")));

        RpaCompleted completed = awaitEvent(documentId);
        assertThat(MockPortal.invoices(invoiceNo)).singleElement().satisfies(entered -> {
            assertThat(entered.refNo()).isEqualTo(completed.portalRefNo());
            assertThat(entered.supplierVkn()).isEqualTo("4810293756");
            assertThat(entered.supplierName()).isEqualTo("Anadolu Rulman A.Ş.");
            assertThat(entered.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(entered.dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(entered.grandTotal()).isEqualByComparingTo("5100.00");
            assertThat(entered.vatTotal()).isEqualByComparingTo("850.00");
            assertThat(entered.currency()).isEqualTo("TRY");
        });
        assertThat(completed.foundExisting()).isFalse();
        assertThat(submission(documentId)).isEqualTo(new Submission("SUBMITTED", completed.portalRefNo(), 1));
    }

    @Test
    void redeliveredCommandEntersInvoiceOnce() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = "DUP-" + UUID.randomUUID();
        Message message = message(command(documentId, invoiceNo, "4810293756", new BigDecimal("5100.00")));

        rabbitTemplate.send("invoice.commands", "rpa.post", message);
        awaitEvent(documentId);
        rabbitTemplate.send("invoice.commands", "rpa.post", message);

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6))
                .until(() -> outboxCount(documentId) == 1 && MockPortal.invoices(invoiceNo).size() == 1);
    }

    @Test
    void alreadySubmittedDocumentIsReportedWithoutTouchingPortal() {
        // Aynı belge yeni bir messageId ile gelirse (inbox ayırt edemez) kayıt numarası yeniden bildirilir.
        UUID documentId = UUID.randomUUID();
        String invoiceNo = "OLD-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO portal_submissions
                            (document_id, supplier_vkn, invoice_no, status, portal_ref_no, attempt_count)
                        VALUES (:id, '4810293756', :invoiceNo, 'SUBMITTED', '777', 1)
                        """).param("id", documentId).param("invoiceNo", invoiceNo).update();

        send(command(documentId, invoiceNo, "4810293756", new BigDecimal("5100.00")));

        assertThat(awaitEvent(documentId).portalRefNo()).isEqualTo("777");
        assertThat(MockPortal.invoices(invoiceNo)).isEmpty();
        assertThat(submission(documentId)).isEqualTo(new Submission("SUBMITTED", "777", 1));
    }

    /**
     * B-37 (FR-R5, FR-R6): geçici hata bekleme odasında 30 sn bekleyip yeniden denenir (teslim limiti tüketilmez);
     * deneme sınırı (bu sınıfta 2) dolunca doğrudan DLQ. Her başarısız deneme adım ve ekran görüntüsüyle kaydedilir.
     */
    @Test
    void portalErrorIsRetriedThroughWaitingRoomThenDeadLetteredWithEveryAttemptRecorded() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = MockPortal.FAILING_PREFIX + UUID.randomUUID();

        send(command(documentId, invoiceNo, "4810293756", new BigDecimal("5100.00")));

        Message deadLettered = rabbitTemplate.receive(DLQ, RETRY_TIMEOUT.toMillis());
        assertThat(deadLettered).isNotNull();
        // İlk ölüm bekleme odasındaki TTL, DLQ'ya düşüren son ölüm reddetme.
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-first-death-reason"))
                .isEqualTo("expired");
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-last-death-reason"))
                .isEqualTo("rejected");
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-last-death-queue"))
                .isEqualTo("rpa.post-to-portal.q");
        // Her deneme portala gitti, hiçbiri kaydedilmedi.
        assertThat(submission(documentId)).isEqualTo(new Submission("IN_PROGRESS", null, 2));
        assertThat(MockPortal.invoices(invoiceNo)).isEmpty();
        assertThat(outboxCount(documentId, "RpaCompleted")).isZero();
        assertThat(attempts(documentId)).extracting(Attempt::attemptNo).containsExactly(1, 2);
        assertThat(attempts(documentId)).allSatisfy(attempt -> {
            assertThat(attempt.failedStep()).isEqualTo("sonucu okuma");
            assertThat(attempt.error()).contains("Portal hata sayfası döndü");
            assertThat(Path.of(attempt.screenshotPath())).isRegularFile().isNotEmptyFile();
        });
    }

    /**
     * B-38: önceki tur deneme hakkını bitirmiş (yönetici yeniden işletti); yeni komut (yeni kimlik) kendi turunu
     * başlatır ve ilk hatada DLQ'ya değil bekleme odasına gider.
     */
    @Test
    void reprocessedInvoiceStartsANewRoundWithItsOwnAttempts() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = MockPortal.FAILING_PREFIX + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO portal_submissions
                            (document_id, supplier_vkn, invoice_no, status, attempt_count, command_id, round_attempts)
                        VALUES (:id, '4810293756', :invoiceNo, 'IN_PROGRESS', 2, :previous, 2)
                        """).param("id", documentId).param("invoiceNo", invoiceNo)
                .param("previous", UUID.randomUUID()).update();
        UUID command = UUID.randomUUID();

        rabbitTemplate.send("invoice.commands", "rpa.post", converter.toAmqpMessage(
                MessageEnvelope.of(command, documentId, MessageType.POST_TO_PORTAL),
                command(documentId, invoiceNo, "4810293756", new BigDecimal("5100.00"))));

        await().atMost(TIMEOUT).until(() -> jdbc.sql("""
                        SELECT count(*) FROM outbox WHERE id = :id AND exchange = 'invoice.retry'
                        """).param("id", command).query(Long.class).single() == 1);
        assertThat(jdbc.sql("""
                        SELECT attempt_count || '/' || round_attempts FROM portal_submissions WHERE document_id = :id
                        """).param("id", documentId).query(String.class).single()).isEqualTo("3/1");
        // Turun ikinci (son) denemesi de düşer: mesaj DLQ'da biter, sonraki testlere başıboş mesaj kalmaz.
        assertThat(rabbitTemplate.receive(DLQ, RETRY_TIMEOUT.toMillis())).isNotNull();
        assertThat(jdbc.sql("""
                        SELECT attempt_count || '/' || round_attempts FROM portal_submissions WHERE document_id = :id
                        """).param("id", documentId).query(String.class).single()).isEqualTo("4/2");
    }

    @Test
    void portalValidationErrorIsDeadLetteredWithoutRetry() {
        UUID documentId = UUID.randomUUID();
        String invoiceNo = "VKN-" + UUID.randomUUID();

        send(command(documentId, invoiceNo, "123", new BigDecimal("5100.00")));

        Message deadLettered = rabbitTemplate.receive(DLQ, TIMEOUT.toMillis());
        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-first-death-reason"))
                .isEqualTo("rejected");
        assertThat(submission(documentId)).isEqualTo(new Submission("IN_PROGRESS", null, 1));
        assertThat(MockPortal.invoices(invoiceNo)).isEmpty();
        // Kalıcı hata da iz bırakır (FR-R6).
        assertThat(attempts(documentId)).singleElement().satisfies(attempt -> {
            assertThat(attempt.failedStep()).isEqualTo("sonucu okuma");
            assertThat(attempt.error()).contains("VKN 10 haneli olmalı");
            assertThat(Path.of(attempt.screenshotPath())).isRegularFile();
        });
    }

    record Attempt(int attemptNo, String failedStep, String error, String screenshotPath) {
    }

    private List<Attempt> attempts(UUID documentId) {
        return jdbc.sql("""
                        SELECT attempt_no, failed_step, error, screenshot_path FROM rpa_attempts
                        WHERE document_id = :id ORDER BY attempt_no
                        """).param("id", documentId).query(Attempt.class).list();
    }

    @Test
    void amountWithMoreThanTwoDecimalsIsDeadLetteredBeforeTouchingPortal() {
        UUID documentId = UUID.randomUUID();

        send(command(documentId, "DEC-" + UUID.randomUUID(), "4810293756", new BigDecimal("5100.005")));

        Message deadLettered = rabbitTemplate.receive(DLQ, TIMEOUT.toMillis());
        assertThat(deadLettered).isNotNull();
        assertThat(deadLettered.getMessageProperties().<String>getHeader("x-first-death-reason"))
                .isEqualTo("rejected");
        assertThat(jdbc.sql("SELECT count(*) FROM portal_submissions WHERE document_id = :id")
                .param("id", documentId).query(Long.class).single()).isZero();
    }

    record Submission(String status, String portalRefNo, int attemptCount) {
    }

    private Submission submission(UUID documentId) {
        return jdbc.sql("SELECT status, portal_ref_no, attempt_count FROM portal_submissions WHERE document_id = :id")
                .param("id", documentId).query(Submission.class).single();
    }

    private static PostToPortal command(UUID documentId, String invoiceNo, String vkn, BigDecimal grandTotal) {
        return new PostToPortal(documentId, vkn, "Anadolu Rulman A.Ş.", invoiceNo, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 1), grandTotal, new BigDecimal("850.00"), "TRY");
    }

    private Message message(PostToPortal command) {
        return converter.toAmqpMessage(
                MessageEnvelope.of(UUID.randomUUID(), command.documentId(), MessageType.POST_TO_PORTAL), command);
    }

    private void send(PostToPortal command) {
        rabbitTemplate.send("invoice.commands", "rpa.post", message(command));
    }

    private RpaCompleted awaitEvent(UUID documentId) {
        await().atMost(TIMEOUT).until(() -> outboxCount(documentId) == 1);
        String payload = jdbc.sql("""
                        SELECT payload::text FROM outbox
                        WHERE aggregate_id = :id AND message_type = 'RpaCompleted'
                        """)
                .param("id", documentId).query(String.class).single();
        return converter.readJson(payload, RpaCompleted.class);
    }

    private long outboxCount(UUID documentId, String messageType) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND message_type = :type")
                .param("id", documentId).param("type", messageType).query(Long.class).single();
    }

    private long outboxCount(UUID documentId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id").param("id", documentId)
                .query(Long.class).single();
    }
}
