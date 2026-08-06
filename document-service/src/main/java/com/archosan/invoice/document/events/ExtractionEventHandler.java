package com.archosan.invoice.document.events;

import com.archosan.invoice.document.persistence.DocumentRepository;
import com.archosan.invoice.document.persistence.InvoiceDataRepository;
import com.archosan.invoice.document.settings.Settings;
import com.archosan.invoice.document.statemachine.StatusTransitions;
import com.archosan.invoice.document.statemachine.TransitionOutcome;
import com.archosan.invoice.document.statemachine.Trigger;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.message.ExtractionCompleted;
import com.archosan.invoice.messaging.message.ExtractionFailed;
import com.archosan.invoice.messaging.message.InvoiceFields;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

import static com.archosan.invoice.document.DocumentStatus.DUPLICATE_SUSPECTED;
import static com.archosan.invoice.document.DocumentStatus.EXTRACTED;
import static com.archosan.invoice.document.DocumentStatus.NEEDS_REVIEW;
import static com.archosan.invoice.document.DocumentStatus.RECEIVED;
import static com.archosan.invoice.document.DocumentStatus.VALIDATED;

/** {@code document.extraction-events.q}: {@code ExtractionCompleted}, {@code ExtractionFailed}. */
@Component
public class ExtractionEventHandler {

    private final StatusTransitions transitions;
    private final DocumentRepository documents;
    private final InvoiceDataRepository invoiceData;
    private final Settings settings;
    private final ComplianceDispatch compliance;
    private final DuplicateCheck duplicates;

    public ExtractionEventHandler(StatusTransitions transitions, DocumentRepository documents,
            InvoiceDataRepository invoiceData, Settings settings, ComplianceDispatch compliance,
            DuplicateCheck duplicates) {
        this.transitions = transitions;
        this.documents = documents;
        this.invoiceData = invoiceData;
        this.settings = settings;
        this.compliance = compliance;
        this.duplicates = duplicates;
    }

    /**
     * {@code RECEIVED → EXTRACTED}, alanları yaz, sonra aynı transaction'da öncelik kuralıyla (§4.1)
     * {@code NEEDS_REVIEW} > {@code DUPLICATE_SUSPECTED} > {@code VALIDATED} + {@code CheckCompliance}. Güven düşükse
     * VKN ve fatura no da güvenilmez, mükerrer kontrolü yapılmaz. Alanlar güveni düşük kayıtta da yazılır; uzman
     * onları görür.
     */
    void onCompleted(MessageEnvelope envelope, ExtractionCompleted event) {
        UUID id = event.documentId();
        Trigger received = Trigger.message(Actors.EXTRACTION, envelope, event, DocumentQueues.EXTRACTION_EVENTS);
        if (transitions.apply(id, RECEIVED, EXTRACTED, received) == TransitionOutcome.STALE) {
            return;
        }

        InvoiceFields fields = event.fields();
        documents.updateExtractedFields(id, fields, event.confidenceScore());
        if (fields != null) {
            invoiceData.save(id, fields, event.ruleResults(), InvoiceDataRepository.Source.LLM);
        }

        ReviewDecision decision = ReviewDecision.decide(fields, event.confidenceScore(),
                settings.confidenceThreshold());
        if (decision.target() != VALIDATED) {
            transitions.apply(id, EXTRACTED, decision.target(), received.as(Actors.DOCUMENT, decision.reason()));
            return;
        }
        Optional<String> duplicate = duplicates.suspicion(id, fields);
        if (duplicate.isPresent()) {
            transitions.apply(id, EXTRACTED, DUPLICATE_SUSPECTED, received.as(Actors.DOCUMENT, duplicate.get()));
            return;
        }
        transitions.apply(id, EXTRACTED, VALIDATED, received.as(Actors.DOCUMENT, decision.reason()));
        compliance.dispatch(id, fields);
    }

    /** {@code RECEIVED → NEEDS_REVIEW}; bir iş sonucudur, DLQ değil (US-05). */
    void onFailed(MessageEnvelope envelope, ExtractionFailed event) {
        String reason = event.reason() + (event.detail() == null ? "" : ": " + event.detail());
        Trigger trigger = Trigger.message(Actors.EXTRACTION, envelope, event, DocumentQueues.EXTRACTION_EVENTS);
        transitions.apply(event.documentId(), RECEIVED, NEEDS_REVIEW, trigger.as(Actors.EXTRACTION, reason));
    }
}
