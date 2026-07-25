package com.archosan.invoice.compliance.check;

import com.archosan.invoice.compliance.ComplianceProperties;
import com.archosan.invoice.compliance.contract.ContractRepository;
import com.archosan.invoice.compliance.contract.ContractRepository.Contract;
import com.archosan.invoice.compliance.contract.ContractRepository.RetrievedChunk;
import com.archosan.invoice.compliance.ingest.ContractEmbedder;
import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Check;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Finding;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Result;
import com.archosan.invoice.messaging.message.InvoiceLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Uyum kontrolü (B-46, FR-C2–C4, US-06, US-07; §4.3 "Retrieval ve karşılaştırma"):
 * <ol>
 *   <li>Sözleşme SQL ile seçilir: 0 → {@code NO_CONTRACT}, 2+ → {@code CONTRACT_CONFLICT}; LLM'e gidilmez.</li>
 *   <li>Her farklı kalem için "{açıklama} birim fiyat", vade için tek sorgu; sözleşmeye filtrelenmiş tam tarama.</li>
 *   <li>LLM maddeden değeri ve alıntıyı çıkarır ({@link ClauseValueClient}); alıntı chunk'ta, değer alıntıda
 *       aranır ({@link Grounding}).</li>
 *   <li>Karşılaştırma burada: fatura birim fiyatı &gt; sözleşme fiyatı, fatura vadesi ≠ sözleşme vadesi.</li>
 * </ol>
 * Bulgu yalnızca aykırılık ve güvenilmez değer için yazılır; bulgu yoksa {@code COMPLIANT}, varsa
 * {@code NON_COMPLIANT}. Kararı (onaya gönderme) document-service verir (FR-C4). Birim karşılaştırılmaz: faturada birim
 * yoktur; sözleşmedeki birim bulguda gösterilir.
 */
@Component
class ComplianceEvaluator {

    static final String PRICE_QUERY = "%s birim fiyat";
    static final String PRICE_QUESTION = "\"%s\" kaleminin sözleşmedeki birim fiyatı nedir (KDV hariç)?";
    static final String TERM_QUERY = "ödeme vadesi, fatura bedeli kaç gün içinde ödenir";
    static final String TERM_QUESTION = "Fatura bedeli fatura tarihinden itibaren kaç gün içinde ödenir?";

    private static final Logger log = LoggerFactory.getLogger(ComplianceEvaluator.class);

    /** @param model LLM'e gidildiyse modeli, gidilmediyse {@code null} */
    record Evaluation(Result result, UUID contractId, List<Finding> findings, List<Long> retrievedChunkIds,
            String model) {
    }

    private final ContractRepository contracts;
    private final ContractEmbedder embedder;
    private final ClauseValueClient llm;
    private final int topK;

    ComplianceEvaluator(ContractRepository contracts, ContractEmbedder embedder, ClauseValueClient llm,
            ComplianceProperties properties) {
        this.contracts = contracts;
        this.embedder = embedder;
        this.llm = llm;
        this.topK = properties.retrieval().topK();
    }

    Evaluation evaluate(CheckCompliance command) {
        List<Contract> valid = command.invoiceDate() == null
                ? List.of()
                : contracts.findValid(command.supplierVkn(), command.invoiceDate());
        if (valid.isEmpty()) {
            return new Evaluation(Result.NO_CONTRACT, null, List.of(new Finding(Check.CONTRACT_VALIDITY, null, null,
                    String.valueOf(command.invoiceDate()), null, true)), List.of(), null);
        }
        if (valid.size() > 1) {
            String conflicting = valid.stream()
                    .map(c -> c.id() + " (" + c.validFrom() + " – " + c.validTo() + ")")
                    .collect(Collectors.joining(", "));
            return new Evaluation(Result.CONTRACT_CONFLICT, null, List.of(new Finding(Check.CONTRACT_VALIDITY, null,
                    null, String.valueOf(command.invoiceDate()), conflicting, true)), List.of(), null);
        }

        Contract contract = valid.getFirst();
        List<Finding> findings = new ArrayList<>();
        Set<Long> retrieved = new LinkedHashSet<>();
        for (Map.Entry<String, BigDecimal> item : highestPricePerItem(command.lines()).entrySet()) {
            checkPrice(contract.id(), item.getKey(), item.getValue(), findings, retrieved);
        }
        checkTerm(contract.id(), command, findings, retrieved);
        Result result = findings.isEmpty() ? Result.COMPLIANT : Result.NON_COMPLIANT;
        return new Evaluation(result, contract.id(), findings, List.copyOf(retrieved), llm.model());
    }

    private void checkPrice(UUID contractId, String item, BigDecimal invoicePrice, List<Finding> findings,
            Set<Long> retrieved) {
        List<RetrievedChunk> chunks = retrieve(contractId, PRICE_QUERY.formatted(item), retrieved);
        Optional<ClauseValue> answer = llm.ask(PRICE_QUESTION.formatted(item), chunks);
        String invoiceValue = item + ": " + invoicePrice.toPlainString();
        Optional<BigDecimal> contractPrice = answer
                .filter(a -> a.found() && Grounding.isQuoted(a.quote(), chunks))
                .flatMap(a -> Grounding.price(a.value(), a.quote()));
        if (contractPrice.isEmpty()) {
            log.warn("Fiyat maddesi güvenilir okunamadı: kalem={}, yanıt={}", item, answer.orElse(null));
            findings.add(unreliable(Check.UNIT_PRICE, answer, invoiceValue));
            return;
        }
        if (invoicePrice.compareTo(contractPrice.get()) > 0) {
            ClauseValue a = answer.orElseThrow();
            findings.add(new Finding(Check.UNIT_PRICE, a.clauseNo(), Grounding.normalize(a.quote()), invoiceValue,
                    contractPrice.get().toPlainString() + (a.unit() == null ? "" : " / " + a.unit()), true));
        }
    }

    private void checkTerm(UUID contractId, CheckCompliance command, List<Finding> findings, Set<Long> retrieved) {
        if (command.invoiceDate() == null || command.dueDate() == null) {
            findings.add(new Finding(Check.PAYMENT_TERM, null, null, "vade ya da fatura tarihi yok", null, false));
            return;
        }
        long invoiceDays = ChronoUnit.DAYS.between(command.invoiceDate(), command.dueDate());
        List<RetrievedChunk> chunks = retrieve(contractId, TERM_QUERY, retrieved);
        Optional<ClauseValue> answer = llm.ask(TERM_QUESTION, chunks);
        String invoiceValue = invoiceDays + " gün";
        Optional<Integer> contractDays = answer
                .filter(a -> a.found() && Grounding.isQuoted(a.quote(), chunks))
                .flatMap(a -> Grounding.days(a.value(), a.quote()));
        if (contractDays.isEmpty()) {
            log.warn("Vade maddesi güvenilir okunamadı: yanıt={}", answer.orElse(null));
            findings.add(unreliable(Check.PAYMENT_TERM, answer, invoiceValue));
            return;
        }
        if (invoiceDays != contractDays.get()) {
            ClauseValue a = answer.orElseThrow();
            findings.add(new Finding(Check.PAYMENT_TERM, a.clauseNo(), Grounding.normalize(a.quote()), invoiceValue,
                    contractDays.get() + " gün", true));
        }
    }

    private List<RetrievedChunk> retrieve(UUID contractId, String query, Set<Long> retrieved) {
        List<RetrievedChunk> chunks = contracts.nearest(contractId, embedder.embedQuery(query), topK);
        chunks.forEach(c -> retrieved.add(c.id()));
        return chunks;
    }

    /** Değer bulunamadı, alıntı chunk'ta yok ya da değer alıntıda yazılı değil: insan baksın. */
    private static Finding unreliable(Check check, Optional<ClauseValue> answer, String invoiceValue) {
        return new Finding(check, answer.map(ClauseValue::clauseNo).orElse(null),
                answer.map(ClauseValue::quote).orElse(null), invoiceValue,
                answer.map(ClauseValue::value).map(BigDecimal::toPlainString).orElse(null), false);
    }

    /** Aynı açıklama birden çok satırda farklı fiyatla geçebilir; en yükseği karşılaştırılır, LLM bir kez sorulur. */
    private static Map<String, BigDecimal> highestPricePerItem(List<InvoiceLine> lines) {
        Map<String, BigDecimal> items = new LinkedHashMap<>();
        for (InvoiceLine line : lines) {
            if (line.description() != null && line.unitPrice() != null) {
                items.merge(line.description().strip(), line.unitPrice(), BigDecimal::max);
            }
        }
        return items;
    }
}
