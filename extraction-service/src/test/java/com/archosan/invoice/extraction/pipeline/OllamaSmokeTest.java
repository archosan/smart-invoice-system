package com.archosan.invoice.extraction.pipeline;

import com.archosan.invoice.extraction.ExtractionIntegrationTest;
import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.extraction.llm.LlmExtraction;
import com.archosan.invoice.extraction.llm.LlmInvoiceClient;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import com.archosan.invoice.extraction.text.PdfTextExtractor;
import com.archosan.invoice.extraction.text.TextExtraction;
import com.archosan.invoice.extraction.validation.ConfidenceScore;
import com.archosan.invoice.extraction.validation.InvoiceValidator;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.RuleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Gerçek Ollama ile duman testi (M2 sonu): 10 sentetik faturayı servisin gerçek bileşenlerinden geçirip beklenen
 * alanlarla karşılaştırır. Normal build'de çalışmaz; host'ta Ollama ve model gerekir:
 *
 * <pre>
 * ./mvnw -pl extraction-service -am verify -Dsmoke.ollama=true -Dtest=OllamaSmokeTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * Prompt sürümü ve bağlam penceresi karşılaştırma için değiştirilebilir: {@code -Dsmoke.prompt=v1},
 * {@code -Dsmoke.numCtx=4096} (varsayılan application.yml'dakiler); yalnız bazı faturalar için
 * {@code -Dsmoke.only=invoice-05.pdf,invoice-06.pdf}. Rapor
 * {@code extraction-service/target/ollama-smoke-report-<prompt>-<numCtx>.md}'ye yazılır. Test başarısız olmaz;
 * ölçüm içindir (B-29).
 */
@EnabledIfSystemProperty(named = "smoke.ollama", matches = "true")
class OllamaSmokeTest extends ExtractionIntegrationTest {

    @DynamicPropertySource
    static void realOllama(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> System.getProperty("smoke.ollama.url", "http://localhost:11434"));
        // İlk istekte model belleğe yüklenir.
        registry.add("invoice.extraction.llm.timeout", () -> "300s");
        if (System.getProperty("smoke.prompt") != null) {
            registry.add("invoice.extraction.llm.prompt-version", () -> System.getProperty("smoke.prompt"));
        }
        if (System.getProperty("smoke.numCtx") != null) {
            registry.add("invoice.extraction.llm.num-ctx", () -> System.getProperty("smoke.numCtx"));
        }
    }

    @Autowired
    private PdfTextExtractor textExtractor;
    @Autowired
    private LlmExtraction llmExtraction;
    @Autowired
    private InvoiceValidator validator;
    @Autowired
    private ConfidenceScore confidence;
    @Autowired
    private LlmInvoiceClient llm;
    @Autowired
    private ExtractionProperties properties;

    @Test
    void runSyntheticSetAgainstRealOllama() throws Exception {
        String variant = llm.promptVersion() + "-" + properties.llm().numCtx();
        StringBuilder report = new StringBuilder("# Ollama duman testi\n\n")
                .append("Model `").append(llm.model()).append("`, prompt `").append(llm.promptVersion())
                .append("`, num_ctx ").append(properties.llm().numCtx()).append("\n\n")
                .append("| Fatura | Beklenen | Süre (sn) | Deneme | Skor | Kalan kurallar | Alan farkları |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        StringBuilder details = new StringBuilder("\n## Ayrıntılar\n");

        List<String> only = System.getProperty("smoke.only") == null ? List.of()
                : List.of(System.getProperty("smoke.only").split(","));
        for (SyntheticInvoice invoice : SyntheticInvoices.all()) {
            if (!only.isEmpty() && !only.contains(invoice.file())) {
                continue;
            }
            Path pdf = INVOICES.resolve(invoice.file());
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pdf)));
            long start = System.nanoTime();
            TextExtraction text = textExtractor.extract(pdf.toUri().toString(), sha);
            String row;
            if (text instanceof TextExtraction.NoText noText) {
                row = cells(invoice, start, "-", "-", "-", "NO_TEXT: " + noText.detail());
            } else {
                LlmExtraction.Result result = llmExtraction.extract(((TextExtraction.Extracted) text).text());
                switch (result) {
                    case LlmExtraction.Exhausted exhausted -> {
                        row = cells(invoice, start, String.valueOf(exhausted.attempts()), "-", "-",
                                "BÜTÇE TÜKENDİ: " + exhausted.lastError());
                        // Başarısız denemelerin ham çıktısı: modelin nerede saptığını görmek için.
                        exhausted.history().forEach(attempt -> details.append("\n### ").append(invoice.file())
                                .append(" — deneme ").append(attempt.attemptNo()).append(": ").append(attempt.error())
                                .append("\n\n```\n").append(attempt.rawOutput()).append("\n```\n"));
                    }
                    case LlmExtraction.Succeeded ok -> {
                        InvoiceFields fields = InvoiceMapper.map(ok.invoice());
                        List<RuleResult> rules = validator.validate(ok.invoice(), fields);
                        BigDecimal score = confidence.score(rules);
                        List<String> failed = rules.stream().filter(r -> !r.passed())
                                .map(r -> r.rule() + " (" + r.detail() + ")").toList();
                        List<String> diffs = diff(invoice.fields(), fields);
                        row = cells(invoice, start, String.valueOf(ok.attempts()), score.toPlainString(),
                                failed.isEmpty() ? "—" : String.join("<br>", failed),
                                diffs.isEmpty() ? "—" : String.join("<br>", diffs));
                        details.append("\n### ").append(invoice.file()).append("\n\n```json\n")
                                .append(ok.history().getLast().rawOutput()).append("\n```\n");
                    }
                }
            }
            report.append(row).append('\n');
            System.out.println("SMOKE " + row);
        }

        Path out = Path.of("target/ollama-smoke-report-" + variant + ".md");
        Files.writeString(out, report.append(details).toString());
        System.out.println("SMOKE rapor: " + out.toAbsolutePath());
    }

    private static String cells(SyntheticInvoice invoice, long start, String attempts, String score, String rules,
            String diffs) {
        double seconds = (System.nanoTime() - start) / 1e9;
        return "| %s | %s | %.1f | %s | %s | %s | %s |".formatted(invoice.file(), invoice.expectedOutcome(), seconds,
                attempts, score, rules, diffs);
    }

    private static List<String> diff(InvoiceFields expected, InvoiceFields actual) {
        List<String> diffs = new ArrayList<>();
        same(diffs, "supplierName", expected.supplierName(), actual.supplierName());
        same(diffs, "supplierVkn", expected.supplierVkn(), actual.supplierVkn());
        same(diffs, "invoiceNo", expected.invoiceNo(), actual.invoiceNo());
        same(diffs, "invoiceDate", expected.invoiceDate(), actual.invoiceDate());
        same(diffs, "dueDate", expected.dueDate(), actual.dueDate());
        same(diffs, "currency", expected.currency(), actual.currency());
        amount(diffs, "subtotal", expected.subtotal(), actual.subtotal());
        amount(diffs, "vatTotal", expected.vatTotal(), actual.vatTotal());
        amount(diffs, "grandTotal", expected.grandTotal(), actual.grandTotal());
        if (expected.lines().size() != actual.lines().size()) {
            diffs.add("kalem sayısı " + expected.lines().size() + " → " + actual.lines().size());
        } else {
            for (int i = 0; i < expected.lines().size(); i++) {
                InvoiceLine e = expected.lines().get(i);
                InvoiceLine a = actual.lines().get(i);
                String label = (i + 1) + ". kalem ";
                same(diffs, label + "açıklama", e.description(), a.description());
                amount(diffs, label + "miktar", e.quantity(), a.quantity());
                amount(diffs, label + "birim fiyat", e.unitPrice(), a.unitPrice());
                same(diffs, label + "KDV", e.vatRate(), a.vatRate());
            }
        }
        return diffs;
    }

    private static void same(List<String> diffs, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            diffs.add(field + ": `" + expected + "` → `" + actual + "`");
        }
    }

    private static void amount(List<String> diffs, String field, BigDecimal expected, BigDecimal actual) {
        if (expected == null ? actual != null : actual == null || expected.compareTo(actual) != 0) {
            diffs.add(field + ": `" + expected + "` → `" + actual + "`");
        }
    }
}
