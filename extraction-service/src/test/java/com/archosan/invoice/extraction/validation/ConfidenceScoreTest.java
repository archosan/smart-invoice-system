package com.archosan.invoice.extraction.validation;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.messaging.message.RuleResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Varsayılan ağırlıklar ve varsayılan eşik (0,80) ile formülün davranışı (A5 ilk sürüm). */
class ConfidenceScoreTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.80");
    private static final List<String> ALL = List.of(Rules.REQUIRED_FIELDS_PRESENT, Rules.VKN_10_DIGITS,
            Rules.LINES_SUM_EQUALS_SUBTOTAL, Rules.SUBTOTAL_PLUS_VAT_EQUALS_TOTAL, Rules.INVOICE_DATE_NOT_AFTER_DUE_DATE,
            Rules.AMOUNTS_PARSED, Rules.VAT_MATCHES_LINES);

    private final ConfidenceScore confidence = new ConfidenceScore(ExtractionProperties.of(Path.of("/x"), 20,
            new ExtractionProperties.Llm("m", Duration.ofSeconds(1), 2, 1, Duration.ofSeconds(1), 3,
                    Duration.ofSeconds(30), 8192, 4096, "v2")));

    @Test
    void allRulesPassingScoresOne() {
        assertThat(confidence.score(results(Set.of()))).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    void eachCriticalRuleAlonePushesBelowThreshold() {
        for (String critical : List.of(Rules.REQUIRED_FIELDS_PRESENT, Rules.VKN_10_DIGITS,
                Rules.LINES_SUM_EQUALS_SUBTOTAL, Rules.SUBTOTAL_PLUS_VAT_EQUALS_TOTAL, Rules.AMOUNTS_PARSED)) {
            assertThat(confidence.score(results(Set.of(critical)))).as(critical).isLessThan(THRESHOLD);
        }
    }

    @Test
    void oneMinorRuleAloneStaysAboveThresholdButTwoDoNot() {
        assertThat(confidence.score(results(Set.of(Rules.INVOICE_DATE_NOT_AFTER_DUE_DATE))))
                .isEqualTo(new BigDecimal("0.85")).isGreaterThanOrEqualTo(THRESHOLD);
        assertThat(confidence.score(results(Set.of(Rules.VAT_MATCHES_LINES)))).isGreaterThanOrEqualTo(THRESHOLD);
        assertThat(confidence.score(results(Set.of(Rules.INVOICE_DATE_NOT_AFTER_DUE_DATE, Rules.VAT_MATCHES_LINES))))
                .isEqualTo(new BigDecimal("0.70")).isLessThan(THRESHOLD);
    }

    @Test
    void scoreHasFloorOfZero() {
        assertThat(confidence.score(results(Set.copyOf(ALL)))).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void brokenSyntheticInvoiceScores() {
        // 9. fatura: kalem toplamı ve dolayısıyla KDV tutarlılığı.
        assertThat(confidence.score(results(Set.of(Rules.LINES_SUM_EQUALS_SUBTOTAL, Rules.VAT_MATCHES_LINES))))
                .isEqualTo(new BigDecimal("0.45"));
    }

    @Test
    void unknownRuleIsAProgrammingError() {
        assertThatThrownBy(() -> confidence.score(List.of(new RuleResult("NEW_RULE", false, "x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<RuleResult> results(Set<String> failing) {
        return ALL.stream().map(rule -> new RuleResult(rule, !failing.contains(rule), failing.contains(rule) ? "x" : null))
                .toList();
    }
}
