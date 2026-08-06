package com.archosan.invoice.document.events;

/** {@code status_transitions.actor} değerleri (DECISIONS.md §4.1, "Üreten"). */
final class Actors {

    static final String EXTRACTION = "extraction-service";
    static final String COMPLIANCE = "compliance-service";
    static final String RPA = "rpa-service";
    static final String DOCUMENT = "document-service";

    private Actors() {
    }
}
