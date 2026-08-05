package com.archosan.invoice.document.deadletter;

import java.util.List;

/** document-service'in ilgilendiği DLQ'lar; topoloji definitions.json'dadır (ADR-17). */
public final class DeadLetterQueues {

    public static final String EXTRACT_INVOICE = "extraction.extract-invoice.dlq";
    public static final String CHECK_COMPLIANCE = "compliance.check-compliance.dlq";
    public static final String POST_TO_PORTAL = "rpa.post-to-portal.dlq";

    /** document-service'in kendi olay kuyruklarının DLQ'ları; tüketilmez, derinlikleri izlenir. */
    public static final List<String> EVENT_DLQS = List.of(
            "document.extraction-events.dlq", "document.compliance-events.dlq", "document.rpa-events.dlq");

    private DeadLetterQueues() {
    }
}
