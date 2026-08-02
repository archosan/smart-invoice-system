package com.archosan.invoice.document;

/** Faturanın durumları (DECISIONS.md §4.1, FR-D3). Geçiş kuralları B-11'de. */
public enum DocumentStatus {
    RECEIVED,
    EXTRACTED,
    VALIDATED,
    COMPLIANCE_CHECKED,
    QUEUED_FOR_RPA,
    POSTED,
    NEEDS_REVIEW,
    PENDING_APPROVAL,
    REJECTED,
    DUPLICATE_SUSPECTED,
    RPA_FAILED
}
