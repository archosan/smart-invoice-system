package com.archosan.invoice.document.statemachine;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.message.RpaCompleted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.archosan.invoice.document.DocumentStatus.POSTED;
import static com.archosan.invoice.document.DocumentStatus.QUEUED_FOR_RPA;
import static com.archosan.invoice.document.DocumentStatus.RPA_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusTransitionsIntegrationTest extends DocumentIntegrationTest {

    private static final String QUEUE = "document.rpa-events.q";

    @Autowired
    private StatusTransitions transitions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void appliesAllowedTransitionAndRecordsIt() {
        UUID id = insertDocument(QUEUED_FOR_RPA);
        OffsetDateTime before = updatedAt(id);
        MessageEnvelope envelope = envelope(id);

        TransitionOutcome outcome = inTx(() -> transitions.apply(id, QUEUED_FOR_RPA, POSTED,
                Trigger.message("rpa-service", envelope, rpaCompleted(id), QUEUE)));

        assertThat(outcome).isEqualTo(TransitionOutcome.APPLIED);
        assertThat(status(id)).isEqualTo("POSTED");
        assertThat(version(id)).isEqualTo(1);
        assertThat(updatedAt(id)).isAfter(before);
        assertThat(jdbc.sql("""
                        SELECT from_status || '>' || to_status || ':' || trigger_event || ':' || actor || ':' || message_id
                        FROM status_transitions WHERE document_id = :id
                        """).param("id", id).query(String.class).list())
                .containsExactly("QUEUED_FOR_RPA>POSTED:RpaCompleted:rpa-service:" + envelope.messageId());
        assertThat(count("dead_letters")).isZero();
    }

    @Test
    void lateMessageIsNotAppliedAndIsRecordedAsLateEvent() {
        UUID id = insertDocument(RPA_FAILED);
        MessageEnvelope envelope = envelope(id);

        TransitionOutcome outcome = inTx(() -> transitions.apply(id, QUEUED_FOR_RPA, POSTED,
                Trigger.message("rpa-service", envelope, rpaCompleted(id), QUEUE)));

        assertThat(outcome).isEqualTo(TransitionOutcome.STALE);
        assertThat(status(id)).isEqualTo("RPA_FAILED");
        assertThat(version(id)).isZero();
        assertThat(count("status_transitions")).isZero();
        assertThat(jdbc.sql("""
                        SELECT kind || ':' || status || ':' || source_queue || ':' || message_type || ':' || message_id
                               || ':' || document_id || ':' || (body->>'portalRefNo')
                        FROM dead_letters
                        """).query(String.class).single())
                .isEqualTo("LATE_EVENT:OPEN:" + QUEUE + ":RpaCompleted:" + envelope.messageId() + ":" + id + ":4711");
    }

    @Test
    void messageForUnknownDocumentIsRecordedAsLateEvent() {
        UUID unknown = UUID.randomUUID();

        TransitionOutcome outcome = inTx(() -> transitions.apply(unknown, QUEUED_FOR_RPA, POSTED,
                Trigger.message("rpa-service", envelope(unknown), rpaCompleted(unknown), QUEUE)));

        assertThat(outcome).isEqualTo(TransitionOutcome.STALE);
        assertThat(jdbc.sql("SELECT document_id FROM dead_letters").query(UUID.class).single()).isEqualTo(unknown);
    }

    @Test
    void staleApiTransitionIsNotRecordedAsLateEvent() {
        UUID id = insertDocument(POSTED);

        TransitionOutcome outcome = inTx(() -> transitions.apply(id, RPA_FAILED, QUEUED_FOR_RPA,
                Trigger.api("REPROCESS", "admin", null)));

        assertThat(outcome).isEqualTo(TransitionOutcome.STALE);
        assertThat(count("dead_letters")).isZero();
    }

    @Test
    void expectedVersionMismatchIsStale() {
        UUID id = insertDocument(RPA_FAILED);

        assertThat(inTx(() -> transitions.apply(id, RPA_FAILED, QUEUED_FOR_RPA,
                Trigger.api("REPROCESS", "admin", null), 5))).isEqualTo(TransitionOutcome.STALE);
        assertThat(inTx(() -> transitions.apply(id, RPA_FAILED, QUEUED_FOR_RPA,
                Trigger.api("REPROCESS", "admin", null), 0))).isEqualTo(TransitionOutcome.APPLIED);
        assertThat(version(id)).isEqualTo(1);
    }

    @Test
    void illegalTransitionThrowsAndWritesNothing() {
        UUID id = insertDocument(POSTED);

        assertThatThrownBy(() -> inTx(() -> transitions.apply(id, POSTED, QUEUED_FOR_RPA,
                Trigger.message("rpa-service", envelope(id), rpaCompleted(id), QUEUE))))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("POSTED → QUEUED_FOR_RPA");
        assertThat(status(id)).isEqualTo("POSTED");
        assertThat(count("status_transitions")).isZero();
        assertThat(count("dead_letters")).isZero();
    }

    @Test
    void refusesToRunOutsideTransaction() {
        UUID id = insertDocument(QUEUED_FOR_RPA);

        assertThatThrownBy(() -> transitions.apply(id, QUEUED_FOR_RPA, POSTED, Trigger.api("X", "test", null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(status(id)).isEqualTo("QUEUED_FOR_RPA");
    }

    @Test
    void concurrentTransitionsFromSameStateApplyOnlyOnce() throws Exception {
        UUID id = insertDocument(QUEUED_FOR_RPA);
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);

        CompletableFuture<TransitionOutcome> first = CompletableFuture.supplyAsync(() -> inTx(() -> {
            TransitionOutcome outcome = transitions.apply(id, QUEUED_FOR_RPA, POSTED,
                    Trigger.message("rpa-service", envelope(id), rpaCompleted(id), QUEUE));
            firstApplied.countDown();
            await(commitFirst);
            return outcome;
        }));
        assertThat(firstApplied.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<TransitionOutcome> second = CompletableFuture.supplyAsync(() -> inTx(() ->
                transitions.apply(id, QUEUED_FOR_RPA, RPA_FAILED,
                        Trigger.message("DLQ:PostToPortal", "document-service",
                                MessageEnvelope.of(UUID.randomUUID(), id, MessageType.POST_TO_PORTAL),
                                new PostToPortal(id, "1234567890", "ACME", "F-1", null, null,
                                        new BigDecimal("120.00"), new BigDecimal("20.00"), "TRY"),
                                "rpa.post-to-portal.dlq"))));
        Thread.sleep(300);
        assertThat(second).isNotDone();
        commitFirst.countDown();

        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactly(TransitionOutcome.APPLIED, TransitionOutcome.STALE);
        assertThat(status(id)).isEqualTo("POSTED");
        assertThat(count("status_transitions")).isEqualTo(1);
        assertThat(count("dead_letters")).isEqualTo(1);
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private UUID insertDocument(DocumentStatus status) {
        UUID id = UUID.randomUUID();
        String sha = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status, updated_at)
                        VALUES (:id, :sha, 'file:///x.pdf', :status, now() - interval '1 minute')
                        """)
                .param("id", id).param("sha", sha).param("status", status.name()).update();
        return id;
    }

    private static MessageEnvelope envelope(UUID documentId) {
        return MessageEnvelope.of(UUID.randomUUID(), documentId, MessageType.RPA_COMPLETED);
    }

    private static RpaCompleted rpaCompleted(UUID documentId) {
        return new RpaCompleted(documentId, "4711", false);
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM documents WHERE id = :id").param("id", id).query(String.class).single();
    }

    private int version(UUID id) {
        return jdbc.sql("SELECT version FROM documents WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    private OffsetDateTime updatedAt(UUID id) {
        return jdbc.sql("SELECT updated_at FROM documents WHERE id = :id").param("id", id)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class)).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
