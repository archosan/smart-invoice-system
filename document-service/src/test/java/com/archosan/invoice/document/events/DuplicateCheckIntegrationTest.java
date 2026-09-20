package com.archosan.invoice.document.events;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.messaging.message.InvoiceFields;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-41: aynı faturanın iki belgesi eşzamanlı karar verirse ikincisi birincinin commit'ini bekler ve onu görür. Kilit
 * olmasa ikisi de kendi transaction'ında diğerini göremez, ikisi de {@code VALIDATED} olurdu.
 */
@TestPropertySource(properties = "invoice.messaging.outbox.relay.enabled=false")
class DuplicateCheckIntegrationTest extends DocumentIntegrationTest {

    private static final InvoiceFields FIELDS = new InvoiceFields("ACME A.Ş.", "1234567890", "FTR-9", null, null,
            List.of(), null, null, null, "TRY");

    @Autowired
    private DuplicateCheck duplicates;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentDocumentOfSameInvoiceWaitsForFirstAndSeesIt() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch firstChecked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Optional<String>> firstResult = CompletableFuture.supplyAsync(() -> inTransaction(() -> {
            insert(first);
            Optional<String> result = duplicates.suspicion(first, FIELDS);
            firstChecked.countDown();
            await(releaseFirst);
            return result;
        }));
        assertThat(firstChecked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Optional<String>> secondResult = CompletableFuture.supplyAsync(() -> inTransaction(() -> {
            insert(second);
            return duplicates.suspicion(second, FIELDS);
        }));
        // Birinci transaction açıkken ikinci kilitte bekler.
        Thread.sleep(Duration.ofMillis(500));
        assertThat(secondResult).isNotDone();

        releaseFirst.countDown();
        assertThat(firstResult.get(10, TimeUnit.SECONDS)).isEmpty();
        assertThat(secondResult.get(10, TimeUnit.SECONDS)).hasValueSatisfying(reason -> assertThat(reason)
                .isEqualTo("mükerrer şüphesi: " + first + " (VALIDATED)"));
    }

    @Test
    void differentInvoicesDoNotWaitForEachOther() throws Exception {
        UUID first = UUID.randomUUID();
        CountDownLatch firstChecked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Optional<String>> firstResult = CompletableFuture.supplyAsync(() -> inTransaction(() -> {
            insert(first);
            Optional<String> result = duplicates.suspicion(first, FIELDS);
            firstChecked.countDown();
            await(releaseFirst);
            return result;
        }));
        assertThat(firstChecked.await(10, TimeUnit.SECONDS)).isTrue();

        UUID other = UUID.randomUUID();
        InvoiceFields otherInvoice = new InvoiceFields("ACME A.Ş.", "1234567890", "FTR-10", null, null, List.of(),
                null, null, null, "TRY");
        Optional<String> otherResult = CompletableFuture.supplyAsync(() -> inTransaction(() -> {
            insert(other, "FTR-10");
            return duplicates.suspicion(other, otherInvoice);
        })).get(5, TimeUnit.SECONDS);

        releaseFirst.countDown();
        assertThat(otherResult).isEmpty();
        assertThat(firstResult.get(10, TimeUnit.SECONDS)).isEmpty();
    }

    private void insert(UUID id) {
        insert(id, "FTR-9");
    }

    private void insert(UUID id, String invoiceNo) {
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status, supplier_vkn, invoice_no)
                        VALUES (:id, :sha, 'file:///data/documents/x.pdf', 'VALIDATED', '1234567890', :invoiceNo)
                        """)
                .param("id", id).param("sha", (id.toString() + id).replace("-", "").substring(0, 64))
                .param("invoiceNo", invoiceNo).update();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
