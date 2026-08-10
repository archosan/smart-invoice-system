package com.archosan.invoice.document.review;

import com.archosan.invoice.messaging.message.RuleResult;

import java.util.List;

/** Düzeltme yapısal kontrolden geçmedi; 422 döner, ihlal edilen kurallarla. */
public class InvalidCorrectionException extends RuntimeException {

    private final List<RuleResult> violations;

    public InvalidCorrectionException(List<RuleResult> violations) {
        super("Düzeltme kurallardan geçmedi: "
                + violations.stream().map(RuleResult::rule).toList());
        this.violations = List.copyOf(violations);
    }

    public List<RuleResult> violations() {
        return violations;
    }
}
