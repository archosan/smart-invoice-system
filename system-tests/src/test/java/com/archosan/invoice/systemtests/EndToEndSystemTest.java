package com.archosan.invoice.systemtests;

import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mutlu yol ve LLM'den gelen hatalar, bütün servisler birlikte (US-01, US-02, US-04, US-05; v1 kabul kriterleri). */
class EndToEndSystemTest extends SystemTest {

    /** {@code settings.approval_amount_threshold} başlangıç değeri (FR-D11, B-39); sistem testleri değiştirmez. */
    private static final BigDecimal APPROVAL_THRESHOLD = new BigDecimal("100000.00");

    /**
     * US-01, US-04 ve kabul kriteri "10 faturanın en az 8'i insansız sonuçlanır: POSTED ya da tutar eşiği gereği
     * PENDING_APPROVAL; toplamları tutmayan NEEDS_REVIEW'a düşer, portala girilmez". LLM iyi bir LLM'dir (basılı
     * değerler); 3. fatura eşiğin üstünde (FR-D11, B-39), 9. fatura bilerek tutarsız, 10. metinsiz.
     */
    @Test
    void syntheticSetReachesExpectedOutcomes() {
        Map<SyntheticInvoice, UUID> uploaded = new LinkedHashMap<>();
        SyntheticInvoices.all().forEach(invoice -> uploaded.put(invoice, upload(invoice)));

        uploaded.forEach((invoice, id) -> {
            String status = api.awaitSettled(id, SETTLE);
            JsonNode detail = api.get(id);
            if (invoice.expectedOutcome() == SyntheticInvoice.ExpectedOutcome.POSTED
                    && invoice.fields().grandTotal().compareTo(APPROVAL_THRESHOLD) > 0) {
                assertThat(status).as(invoice.file()).isEqualTo("PENDING_APPROVAL");
                assertThat(detail.path("compliance").path("result").asString()).isEqualTo("COMPLIANT");
                assertThat(outboxRows(id, "PostToPortal")).as(invoice.file()).isZero();
                assertThat(submission(id)).as(invoice.file()).isNull();
            } else if (invoice.expectedOutcome() == SyntheticInvoice.ExpectedOutcome.POSTED) {
                assertThat(status).as(invoice.file()).isEqualTo("POSTED");
                assertThat(detail.path("portalRefNo").asString()).as(invoice.file()).isNotBlank();
                assertThat(detail.path("compliance").path("result").asString()).isEqualTo("COMPLIANT");
                assertThat(submission(id)).as(invoice.file()).satisfies(s -> {
                    assertThat(s.status()).isEqualTo("SUBMITTED");
                    assertThat(s.portalRefNo()).isEqualTo(detail.path("portalRefNo").asString());
                    assertThat(s.attemptCount()).isEqualTo(1);
                });
            } else {
                assertThat(status).as(invoice.file()).isEqualTo("NEEDS_REVIEW");
                assertThat(outboxRows(id, "PostToPortal")).as(invoice.file()).isZero();
                assertThat(submission(id)).as(invoice.file()).isNull();
            }
        });
        assertThat(uploaded.values().stream().map(api::status)
                .filter(status -> status.equals("POSTED") || status.equals("PENDING_APPROVAL")).count())
                .isGreaterThanOrEqualTo(8);
    }

    /** US-02: aynı PDF'in ikinci yüklemesi yeni kayıt ve portal girişi oluşturmaz. */
    @Test
    void sameFileTwiceCreatesNoSecondRecordOrPortalEntry() {
        SyntheticInvoice invoice = Invoices.renumbered(Invoices.byFile("invoice-02.pdf"), llm);
        byte[] pdf = Invoices.render(invoice);

        DocumentApi.Upload first = api.upload(invoice.file(), pdf);
        assertThat(first.httpStatus()).isEqualTo(202);
        assertThat(api.awaitSettled(first.documentId(), SETTLE)).isEqualTo("POSTED");

        DocumentApi.Upload second = api.upload("baska-ad.pdf", pdf);

        assertThat(second.httpStatus()).isEqualTo(200);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(second.status()).isEqualTo("POSTED");
        String sha = api.get(first.documentId()).path("fileSha256").asString();
        assertThat(documentDb().sql("SELECT count(*) FROM documents WHERE file_sha256 = :sha").param("sha", sha)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(submission(first.documentId()).attemptCount()).isEqualTo(1);
    }

    /** US-05: LLM her denemede şemaya uymayan çıktı verir; sınırlı deneme tükenince NEEDS_REVIEW, portala gidilmez. */
    @Test
    void unparsableLlmOutputEndsInReviewAfterLimitedRetries() {
        SyntheticInvoice invoice = Invoices.renumbered(Invoices.byFile("invoice-06.pdf"), llm);
        llm.answerRaw(invoice.fields().invoiceNo(), "Elbette, işte JSON: {bozuk");

        UUID id = uploadRendered(invoice);

        assertThat(api.awaitSettled(id, SETTLE)).isEqualTo("NEEDS_REVIEW");
        assertThat(extractionDb().sql("""
                        SELECT count(*) FROM extraction_runs WHERE document_id = :id AND parse_error IS NOT NULL
                        """).param("id", id).query(Long.class).single()).isEqualTo(3);
        assertThat(submission(id)).isNull();
        // Kalıcı sonuç: kayıt beklemede kalmaz, bir süre sonra da değişmez.
        assertThat(api.awaitSettled(id, Duration.ofSeconds(5))).isEqualTo("NEEDS_REVIEW");
    }

    /**
     * US-03: aynı fatura (VKN + no) farklı bir PDF ile ikinci kez gelir. İkinci belge DUPLICATE_SUSPECTED olur, portala
     * gidilmez; uzman "mükerrer değil" derse yola devam eder ve portal ön araması ilk girişi bulur (FOUND_EXISTING),
     * portalda ikinci kayıt açılmaz.
     */
    @Test
    void sameInvoiceInDifferentFileIsSuspectedAndNeverEnteredTwice() {
        SyntheticInvoice invoice = Invoices.renumbered(Invoices.byFile("invoice-05.pdf"), llm);
        UUID first = uploadRendered(invoice);
        assertThat(api.awaitSettled(first, SETTLE)).isEqualTo("POSTED");

        UUID second = uploadRendered(invoice);   // render her seferinde farklı dosya (hash) üretir

        assertThat(api.awaitSettled(second, SETTLE)).isEqualTo("DUPLICATE_SUSPECTED");
        assertThat(api.get(second).path("duplicateOf").get(0).asString()).isEqualTo(first.toString());
        assertThat(outboxRows(second, "CheckCompliance")).isZero();
        assertThat(submission(second)).isNull();

        assertThat(api.decideDuplicate(second, false, null)).isEqualTo(200);

        assertThat(api.awaitSettled(second, SETTLE)).isEqualTo("POSTED");
        assertThat(submission(second).status()).isEqualTo("FOUND_EXISTING");
        assertThat(api.get(second).path("portalRefNo").asString())
                .isEqualTo(api.get(first).path("portalRefNo").asString());
    }
}
