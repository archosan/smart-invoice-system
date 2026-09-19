package com.archosan.invoice.document.events;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.messaging.message.InvoiceFields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDecisionTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.80");
    private static final InvoiceFields FIELDS = new InvoiceFields("ACME", "1234567890", "F-1", null, null, List.of(),
            null, null, null, "TRY");

    @ParameterizedTest
    @CsvSource({
            "0.79, NEEDS_REVIEW, güven 0.79 < eşik 0.80",
            "0.7999, NEEDS_REVIEW, güven 0.7999 < eşik 0.80",
            "0.80, VALIDATED, güven 0.80 ≥ eşik 0.80",
            "0.800, VALIDATED, güven 0.800 ≥ eşik 0.80",
            "0.95, VALIDATED, güven 0.95 ≥ eşik 0.80"})
    void comparesScoreWithThreshold(String score, DocumentStatus target, String reason) {
        ReviewDecision decision = ReviewDecision.decide(FIELDS, new BigDecimal(score), THRESHOLD);

        assertThat(decision.target()).isEqualTo(target);
        assertThat(decision.reason()).isEqualTo(reason);
    }

    @Test
    void missingScoreGoesToReview() {
        assertThat(ReviewDecision.decide(FIELDS, null, THRESHOLD).target()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
    }

    @Test
    void missingFieldsGoToReviewEvenWithHighScore() {
        assertThat(ReviewDecision.decide(null, new BigDecimal("0.99"), THRESHOLD).target())
                .isEqualTo(DocumentStatus.NEEDS_REVIEW);
    }
}
