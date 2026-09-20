package com.archosan.invoice.document.events;

import com.archosan.invoice.messaging.message.InvoiceFields;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-D11, B-39: eşik dahil değildir, TRY dışı her zaman onaya gider. */
class ApprovalDecisionTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("100000.00");

    @Test
    void amountEqualToThresholdDoesNotNeedApproval() {
        assertThat(ApprovalDecision.decide(fields("100000.00", "TRY"), THRESHOLD))
                .isEqualTo(new ApprovalDecision(false, "tutar 100000.00 ≤ eşik 100000.00"));
    }

    @Test
    void amountAboveThresholdNeedsApproval() {
        assertThat(ApprovalDecision.decide(fields("100000.01", "TRY"), THRESHOLD))
                .isEqualTo(new ApprovalDecision(true, "tutar 100000.01 > eşik 100000.00"));
    }

    @Test
    void foreignCurrencyNeedsApprovalWhateverTheAmount() {
        assertThat(ApprovalDecision.decide(fields("1.00", "EUR"), THRESHOLD))
                .isEqualTo(new ApprovalDecision(true, "para birimi EUR ≠ TRY"));
    }

    @Test
    void missingAmountOrCurrencyIsLeftToHuman() {
        assertThat(ApprovalDecision.decide(null, THRESHOLD).required()).isTrue();
        assertThat(ApprovalDecision.decide(fields(null, "TRY"), THRESHOLD).required()).isTrue();
        assertThat(ApprovalDecision.decide(fields("10.00", null), THRESHOLD).required()).isTrue();
    }

    @Test
    void zeroThresholdSendsEveryPositiveAmountToApproval() {
        assertThat(ApprovalDecision.decide(fields("0.01", "TRY"), BigDecimal.ZERO).required()).isTrue();
    }

    private static InvoiceFields fields(String grandTotal, String currency) {
        return new InvoiceFields("ACME A.Ş.", "1234567890", "FTR-1", null, null, List.of(), null, null,
                grandTotal == null ? null : new BigDecimal(grandTotal), currency);
    }
}
