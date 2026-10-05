package com.archosan.invoice.systemtests;

import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;

/**
 * Sistem testlerinin tabanı (B-28). Yığın JVM başına bir kez kurulur ({@link SystemStack}); her test kendi
 * yüklediği faturalarla çalışır. Her yükleme benzersiz fatura no'lu yeni bir faturadır ({@link Invoices#renumbered}),
 * testler birbirinin kayıtlarını mükerrer (B-41) ya da portalda mevcut (B-35) görmez.
 */
abstract class SystemTest {

    static final Duration SETTLE = Duration.ofMinutes(3);

    SystemStack stack;
    DocumentApi api;
    FakeLlm llm;

    @BeforeEach
    void stack() {
        stack = SystemStack.get();
        api = stack.documents();
        llm = stack.llm();
        llm.reset();
    }

    @AfterEach
    void resetLlm() {
        llm.reset();
    }

    /** B-16 faturasını yeni bir fatura no ile yükler. */
    UUID upload(SyntheticInvoice invoice) {
        return uploadRendered(Invoices.renumbered(invoice, llm));
    }

    /** Faturayı olduğu gibi (numarası değiştirilmeden) üretip yükler. */
    UUID uploadRendered(SyntheticInvoice invoice) {
        DocumentApi.Upload upload = api.upload(invoice.file(), Invoices.render(invoice));
        if (upload.httpStatus() != 202) {
            throw new IllegalStateException("Yükleme 202 dönmedi: " + upload);
        }
        return upload.documentId();
    }

    JdbcClient documentDb() {
        return stack.database("document_db");
    }

    JdbcClient extractionDb() {
        return stack.database("extraction_db");
    }

    JdbcClient rpaDb() {
        return stack.database("rpa_db");
    }

    record Submission(String status, String portalRefNo, int attemptCount) {
    }

    /** {@code portal_submissions} satırı; portala hiç gidilmediyse {@code null}. */
    Submission submission(UUID documentId) {
        return rpaDb().sql("SELECT status, portal_ref_no, attempt_count FROM portal_submissions WHERE document_id = :id")
                .param("id", documentId).query(Submission.class).optional().orElse(null);
    }

    long outboxRows(UUID documentId, String messageType) {
        return documentDb().sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND message_type = :type")
                .param("id", documentId).param("type", messageType).query(Long.class).single();
    }
}
