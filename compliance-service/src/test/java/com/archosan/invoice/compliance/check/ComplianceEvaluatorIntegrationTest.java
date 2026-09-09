package com.archosan.invoice.compliance.check;

import com.archosan.invoice.compliance.ComplianceIntegrationTest;
import com.archosan.invoice.compliance.contract.ContractUploadService;
import com.archosan.invoice.compliance.testdata.SyntheticContract;
import com.archosan.invoice.compliance.testdata.SyntheticContractGenerator;
import com.archosan.invoice.compliance.testdata.SyntheticContracts;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Check;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Finding;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Result;
import com.archosan.invoice.messaging.message.InvoiceFields;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Uyum kontrolü (B-46, FR-C2–C4): B-44'ün 8 sözleşmesi gerçekten indekslenir, B-16'nın 10 faturası değerlendirilir;
 * beklenen sonuçlar sözleşme kataloğundan gelir. LLM "iyi LLM"dir ({@code ClauseAnswerer}); halüsinasyon senaryoları
 * yanıtı bozar.
 */
class ComplianceEvaluatorIntegrationTest extends ComplianceIntegrationTest {

    private static final Path CONTRACTS = Path.of("src/test/resources/contracts");

    @Autowired
    private ComplianceEvaluator evaluator;
    @Autowired
    private ContractUploadService uploads;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void ingestContractSet() throws IOException {
        for (SyntheticContract contract : SyntheticContracts.all()) {
            try (InputStream in = Files.newInputStream(CONTRACTS.resolve(contract.file()))) {
                uploads.upload(in, contract.supplierVkn(), contract.validFrom(), contract.validTo(), EXPERT);
            }
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.sql(
                "SELECT count(*) FROM contracts WHERE status = 'INGESTING'").query(Long.class).single() == 0);
    }

    @Test
    void everyInvoiceGetsTheResultItsContractsDictate() {
        Map<String, Result> expected = new LinkedHashMap<>();
        SyntheticInvoices.all().forEach(i -> expected.put(i.file(), Result.NO_CONTRACT));
        SyntheticContracts.all().stream().filter(c -> c.expectation().result() != null)
                .forEach(c -> expected.put(c.expectation().invoiceFile(), c.expectation().result()));

        Map<String, Result> actual = new LinkedHashMap<>();
        SyntheticInvoices.all().forEach(i -> actual.put(i.file(), evaluate(i).result()));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void priceAboveContractIsReportedWithClauseAndQuote() {
        ComplianceEvaluator.Evaluation evaluation = evaluate(invoice("invoice-02.pdf"));

        assertThat(evaluation.findings()).containsExactly(new Finding(Check.UNIT_PRICE, "4.1",
                "4.1. Rulman 6204 ZZ için birim fiyat, adet başına 80,00 TL'dir (KDV hariç).", "Rulman 6204 ZZ: 86.50",
                "80.00 / adet", true));
        assertThat(evaluation.contractId()).isNotNull();
        assertThat(evaluation.retrievedChunkIds()).isNotEmpty();
        assertThat(evaluation.model()).isEqualTo("qwen2.5:7b-instruct");
    }

    @Test
    void differentPaymentTermIsReported() {
        assertThat(evaluate(invoice("invoice-04.pdf")).findings()).singleElement().satisfies(f -> {
            assertThat(f.check()).isEqualTo(Check.PAYMENT_TERM);
            assertThat(f.clauseNo()).isEqualTo("5");
            assertThat(f.invoiceValue()).isEqualTo("14 gün");
            assertThat(f.contractValue()).isEqualTo("30 gün");
            assertThat(f.quote()).contains("30 (otuz) gün");
            assertThat(f.reliable()).isTrue();
        });
    }

    /** SQL'de biten sonuçlar LLM'e gitmez. */
    @Test
    void contractSelectionNeverCallsTheLlm() {
        assertThat(evaluate(invoice("invoice-06.pdf")).findings()).singleElement()
                .satisfies(f -> assertThat(f.contractValue()).contains("2026-01-01", "2026-07-01"));
        assertThat(evaluate(invoice("invoice-08.pdf")).result()).isEqualTo(Result.NO_CONTRACT);
        assertThat(LLM.prompts()).isEmpty();
    }

    /** Grounding: alıntı maddede yoksa, değer alıntıda yazılı değilse ya da yanıt çözülemezse bulgu güvenilmez. */
    @Test
    void hallucinatedOrUnusableAnswersAreUnreliable() {
        LLM.rewrite(a -> a.replace("\"quote\":\"", "\"quote\":\"Uydurma: "));
        assertUnreliable();

        LLM.rewrite(a -> a.replace("\"value\":425.00", "\"value\":500.00"));
        assertThat(evaluate(invoice("invoice-01.pdf")).findings()).singleElement()
                .satisfies(f -> assertThat(f.reliable()).isFalse());

        LLM.rewrite(a -> "Elbette! {bozuk");
        assertUnreliable();
    }

    @Test
    void unreachableLlmIsTransientNotAFinding() {
        LLM.failWith(new IllegalStateException("Ollama'ya ulaşılamıyor"));

        assertThatThrownBy(() -> evaluate(invoice("invoice-01.pdf"))).hasMessageContaining("ulaşılamıyor");
    }

    /**
     * system-tests her tedarikçiye faturayla uyumlu bir sözleşme yükler ({@code compliantWith}); temiz faturaların hepsi
     * gerçek yoldan {@code COMPLIANT} olmalı. 48 kalemlik fatura (madde işaretli uzun fiyat listesi) dahil.
     */
    @Test
    void contractGeneratedFromInvoiceMakesItCompliant(@TempDir Path dir) throws IOException {
        jdbc.sql("UPDATE contracts SET status = 'FAILED', failure_reason = 'test' WHERE status = 'READY'").update();
        for (SyntheticInvoice invoice : SyntheticInvoices.all()) {
            if (invoice.expectedOutcome() != SyntheticInvoice.ExpectedOutcome.POSTED) {
                continue;
            }
            Path pdf = dir.resolve("uyumlu-" + invoice.file());
            SyntheticContract contract = SyntheticContracts.compliantWith(invoice, pdf.getFileName().toString());
            SyntheticContractGenerator.write(contract, pdf);
            try (InputStream in = Files.newInputStream(pdf)) {
                uploads.upload(in, contract.supplierVkn(), contract.validFrom(), contract.validTo(), EXPERT);
            }
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.sql(
                "SELECT count(*) FROM contracts WHERE status = 'INGESTING'").query(Long.class).single() == 0);

        for (SyntheticInvoice invoice : SyntheticInvoices.all()) {
            if (invoice.expectedOutcome() == SyntheticInvoice.ExpectedOutcome.POSTED) {
                assertThat(evaluate(invoice).findings()).as(invoice.file()).isEmpty();
            }
        }
    }

    private void assertUnreliable() {
        ComplianceEvaluator.Evaluation evaluation = evaluate(invoice("invoice-01.pdf"));
        assertThat(evaluation.result()).isEqualTo(Result.NON_COMPLIANT);
        assertThat(evaluation.findings()).extracting(Finding::check).containsExactly(Check.UNIT_PRICE,
                Check.PAYMENT_TERM);
        assertThat(evaluation.findings()).noneMatch(Finding::reliable);
    }

    private ComplianceEvaluator.Evaluation evaluate(SyntheticInvoice invoice) {
        InvoiceFields f = invoice.fields();
        return evaluator.evaluate(new CheckCompliance(UUID.randomUUID(), f.supplierVkn(), f.invoiceDate(),
                f.dueDate(), f.lines(), f.subtotal(), f.vatTotal(), f.grandTotal(), f.currency()));
    }

    private static SyntheticInvoice invoice(String file) {
        return SyntheticInvoices.all().stream().filter(i -> i.file().equals(file)).findFirst().orElseThrow();
    }
}
