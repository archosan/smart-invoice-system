package com.archosan.invoice.document.events;

import com.archosan.invoice.document.persistence.DocumentRepository;
import com.archosan.invoice.document.statemachine.StatusTransitions;
import com.archosan.invoice.document.statemachine.TransitionOutcome;
import com.archosan.invoice.document.statemachine.Trigger;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.message.RpaCompleted;
import org.springframework.stereotype.Component;

import static com.archosan.invoice.document.DocumentStatus.POSTED;
import static com.archosan.invoice.document.DocumentStatus.QUEUED_FOR_RPA;

/** {@code document.rpa-events.q}: {@code RpaCompleted}. */
@Component
public class RpaEventHandler {

    private final StatusTransitions transitions;
    private final DocumentRepository documents;

    public RpaEventHandler(StatusTransitions transitions, DocumentRepository documents) {
        this.transitions = transitions;
        this.documents = documents;
    }

    /** {@code QUEUED_FOR_RPA → POSTED} ve portal kayıt numarası (ön aramada bulunan kayıt dahil). */
    void onCompleted(MessageEnvelope envelope, RpaCompleted event) {
        Trigger trigger = Trigger.message(Actors.RPA, envelope, event, DocumentQueues.RPA_EVENTS);
        if (transitions.apply(event.documentId(), QUEUED_FOR_RPA, POSTED, trigger) == TransitionOutcome.APPLIED) {
            documents.updatePortalRefNo(event.documentId(), event.portalRefNo());
        }
    }
}
