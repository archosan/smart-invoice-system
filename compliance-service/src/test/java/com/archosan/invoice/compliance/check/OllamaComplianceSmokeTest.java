package com.archosan.invoice.compliance.check;

import com.archosan.invoice.compliance.contract.ContractUploadService;
import com.archosan.invoice.compliance.testdata.SyntheticContract;
import com.archosan.invoice.compliance.testdata.SyntheticContracts;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Finding;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Result;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.testsupport.AbstractIntegrationTest;
import com.archosan.invoice.testsupport.InfrastructureContainers;
import com.archosan.invoice.testsupport.ServiceDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;

/**
 * Gerçek Ollama ile uyum kontrolü ölçümü (B-46): B-44'ün 8 sözleşmesi bge-m3 ile indekslenir, B-16'nın 10 faturası
 * sohbet modeliyle değerlendirilir; sonuç, bulgular ve süre beklenenle karşılaştırılır. Normal build'de çalışmaz; host'ta
 * Ollama ve iki model gerekir:
 *
 * <pre>
 * ./mvnw -pl compliance-service -am verify -Dsmoke.ollama=true -Dtest=OllamaComplianceSmokeTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Rapor {@code compliance-service/target/compliance-smoke-report.md}. Test başarısız olmaz; ölçüm içindir.
 */
@EnabledIfSystemProperty(named = "smoke.ollama", matches = "true")
@DirtiesContext
class OllamaComplianceSmokeTest extends AbstractIntegrationTest {

    private static final Path CONTRACTS = Path.of("src/test/resources/contracts");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) throws Exception {
        InfrastructureContainers.registerPostgres(registry, ServiceDatabase.COMPLIANCE);
        InfrastructureContainers.registerRedis(registry);
        Path storage = Files.createTempDirectory("contracts-smoke-");
        registry.add("invoice.compliance.storage-dir", storage::toString);
        for (String user : new String[] {"expert", "approver", "admin", "expert-approver"}) {
            registry.add("invoice.compliance.security.users." + user + ".password", () -> UUID.randomUUID().toString());
        }
        registry.add("spring.ai.ollama.base-url", () -> System.getProperty("smoke.ollama.url", "http://localhost:11434"));
        // İlk istekte model belleğe yüklenir.
        registry.add("invoice.compliance.llm.timeout", () -> "300s");
    }

    @Autowired
    private ContractUploadService uploads;
    @Autowired
    private ComplianceEvaluator evaluator;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void runSyntheticSetAgainstRealOllama() throws Exception {
        jdbc.sql("TRUNCATE compliance_checks, contract_chunks, contracts, outbox, inbox").update();
        long ingestStart = System.nanoTime();
        for (SyntheticContract contract : SyntheticContracts.all()) {
            try (InputStream in = Files.newInputStream(CONTRACTS.resolve(contract.file()))) {
                uploads.upload(in, contract.supplierVkn(), contract.validFrom(), contract.validTo(), "smoke");
            }
        }
        await().atMost(Duration.ofMinutes(5)).until(() -> jdbc.sql(
                "SELECT count(*) FROM contracts WHERE status = 'INGESTING'").query(Long.class).single() == 0);
        double ingestSeconds = (System.nanoTime() - ingestStart) / 1e9;

        Map<String, Result> expected = SyntheticContracts.all().stream()
                .filter(c -> c.expectation().result() != null)
                .collect(Collectors.toMap(c -> c.expectation().invoiceFile(), c -> c.expectation().result(),
                        (a, b) -> a));
        StringBuilder report = new StringBuilder("# Uyum kontrolü duman testi (gerçek Ollama)\n\n")
                .append("Sözleşmelerin indekslenmesi: %.1f sn (8 sözleşme)\n\n".formatted(ingestSeconds))
                .append("| Fatura | Beklenen | Sonuç | Süre (sn) | Bulgular |\n| --- | --- | --- | --- | --- |\n");
        int correct = 0;
        for (SyntheticInvoice invoice : SyntheticInvoices.all()) {
            InvoiceFields f = invoice.fields();
            long start = System.nanoTime();
            ComplianceEvaluator.Evaluation evaluation = evaluator.evaluate(new CheckCompliance(UUID.randomUUID(),
                    f.supplierVkn(), f.invoiceDate(), f.dueDate(), f.lines(), f.subtotal(), f.vatTotal(),
                    f.grandTotal(), f.currency()));
            double seconds = (System.nanoTime() - start) / 1e9;
            Result want = expected.getOrDefault(invoice.file(), Result.NO_CONTRACT);
            correct += evaluation.result() == want ? 1 : 0;
            report.append("| %s | %s | %s%s | %.1f | %s |\n".formatted(invoice.file(), want, evaluation.result(),
                    evaluation.result() == want ? "" : " ✗", seconds, evaluation.findings().stream()
                            .map(OllamaComplianceSmokeTest::describe).collect(Collectors.joining("<br>"))));
        }
        report.append("\nDoğru sonuç: ").append(correct).append("/10\n");
        Path out = Path.of("target/compliance-smoke-report.md");
        Files.writeString(out, report);
        System.out.println(report);
    }

    private static String describe(Finding f) {
        return "%s %s%s: fatura %s, sözleşme %s%s".formatted(f.check(), f.clauseNo() == null ? "" : "Madde ",
                f.clauseNo() == null ? "" : f.clauseNo(), f.invoiceValue(), f.contractValue(),
                f.reliable() ? "" : " (güvenilmez; alıntı: " + f.quote() + ")");
    }
}
