package com.archosan.invoice.document.events;

/** document-service'in dinlediği kuyruklar; topoloji definitions.json'dadır (ADR-17). */
public final class DocumentQueues {

    public static final String EXTRACTION_EVENTS = "document.extraction-events.q";
    public static final String COMPLIANCE_EVENTS = "document.compliance-events.q";
    public static final String RPA_EVENTS = "document.rpa-events.q";

    private DocumentQueues() {
    }
}
