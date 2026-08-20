package com.archosan.invoice.extraction.validation;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.messaging.message.RuleResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Güven skoru (FR-E4, B-20; A5'in ilk sürümü): 1,00 − kalan kuralların ağırlıkları toplamı, alt sınır 0, iki ondalık.
 * Skoru yalnızca ölçer; eşik kararı document-service'tedir (ADR-13). Ağırlıklar ayarlanabilir, B-29'da kalibre edilir.
 */
@Component
public class ConfidenceScore {

    private final Map<String, BigDecimal> weights;

    public ConfidenceScore(ExtractionProperties properties) {
        ExtractionProperties.Weights w = properties.validation().weights();
        this.weights = Map.of(
                Rules.REQUIRED_FIELDS_PRESENT, w.requiredFieldsPresent(),
                Rules.VKN_10_DIGITS, w.vkn10Digits(),
                Rules.LINES_SUM_EQUALS_SUBTOTAL, w.linesSumEqualsSubtotal(),
                Rules.SUBTOTAL_PLUS_VAT_EQUALS_TOTAL, w.subtotalPlusVatEqualsTotal(),
                Rules.INVOICE_DATE_NOT_AFTER_DUE_DATE, w.invoiceDateNotAfterDueDate(),
                Rules.AMOUNTS_PARSED, w.amountsParsed(),
                Rules.VAT_MATCHES_LINES, w.vatMatchesLines());
    }

    public BigDecimal score(List<RuleResult> results) {
        BigDecimal score = BigDecimal.ONE;
        for (RuleResult result : results) {
            if (!result.passed()) {
                BigDecimal weight = weights.get(result.rule());
                if (weight == null) {
                    throw new IllegalArgumentException("Ağırlığı tanımsız kural: " + result.rule());
                }
                score = score.subtract(weight);
            }
        }
        return score.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
