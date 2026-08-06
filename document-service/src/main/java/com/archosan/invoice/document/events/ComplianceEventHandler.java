package com.archosan.invoice.document.events;

import com.archosan.invoice.document.persistence.ComplianceResultRepository;
import com.archosan.invoice.document.persistence.InvoiceDataRepository;
import com.archosan.invoice.document.settings.Settings;
import com.archosan.invoice.document.statemachine.StatusTransitions;
import com.archosan.invoice.document.statemachine.TransitionOutcome;
import com.archosan.invoice.document.statemachine.Trigger;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.stream.Collectors;

import static com.archosan.invoice.document.DocumentStatus.COMPLIANCE_CHECKED;
import static com.archosan.invoice.document.DocumentStatus.PENDING_APPROVAL;
import static com.archosan.invoice.document.DocumentStatus.QUEUED_FOR_RPA;
import static com.archosan.invoice.document.DocumentStatus.VALIDATED;

/** {@code document.compliance-events.q}: {@code ComplianceCompleted}. */
@Component
public class ComplianceEventHandler {

    private final StatusTransitions transitions;
    private final ComplianceResultRepository complianceResults;
    private final InvoiceDataRepository invoiceData;
    private final Settings settings;
    private final RpaDispatch rpa;

    public ComplianceEventHandler(StatusTransitions transitions, ComplianceResultRepository complianceResults,
            InvoiceDataRepository invoiceData, Settings settings, RpaDispatch rpa) {
        this.transitions = transitions;
        this.complianceResults = complianceResults;
        this.invoiceData = invoiceData;
        this.settings = settings;
        this.rpa = rpa;
    }

    /**
     * Tek transaction'da, uyumlu sonuçta tutar eşiğine göre (FR-D11, {@link ApprovalDecision}):
     * <ul>
     *   <li>onay gerekmiyorsa {@code VALIDATED → COMPLIANCE_CHECKED → QUEUED_FOR_RPA} ve {@code PostToPortal};</li>
     *   <li>gerekiyorsa {@code VALIDATED → PENDING_APPROVAL}, nedeniyle; komut yazılmaz, onaycı karar verir (B-40).</li>
     * </ul>
     * Uyum sonucu iki yolda da kaydedilir. Karar document-service'indir (FR-C4), geçişin aktörü de odur.
     *
     * <p>{@code NON_COMPLIANT}, {@code NO_CONTRACT}, {@code CONTRACT_CONFLICT} (B-46, US-06, US-07):
     * {@code VALIDATED → PENDING_APPROVAL}, aktör compliance-service, gerekçe sonuç ve bulgu özeti; bulgular
     * {@code compliance_results}'a yazılır, komut yazılmaz. Onaycı bulguları kaydın {@code compliance} alanında görür.
     */
    void onCompleted(MessageEnvelope envelope, ComplianceCompleted event) {
        UUID id = event.documentId();
        Trigger trigger = Trigger.message(Actors.COMPLIANCE, envelope, event, DocumentQueues.COMPLIANCE_EVENTS);
        if (event.result() != ComplianceCompleted.Result.COMPLIANT) {
            if (transitions.apply(id, VALIDATED, PENDING_APPROVAL, trigger.as(Actors.COMPLIANCE, summary(event)))
                    == TransitionOutcome.APPLIED) {
                complianceResults.save(id, event.result(), event.findings());
            }
            return;
        }
        ApprovalDecision approval = ApprovalDecision.decide(invoiceData.findFields(id).orElse(null),
                settings.approvalAmountThreshold());

        if (approval.required()) {
            if (transitions.apply(id, VALIDATED, PENDING_APPROVAL, trigger.as(Actors.DOCUMENT, approval.reason()))
                    == TransitionOutcome.APPLIED) {
                complianceResults.save(id, event.result(), event.findings());
            }
            return;
        }
        if (transitions.apply(id, VALIDATED, COMPLIANCE_CHECKED, trigger) == TransitionOutcome.STALE) {
            return;
        }
        complianceResults.save(id, event.result(), event.findings());

        transitions.apply(id, COMPLIANCE_CHECKED, QUEUED_FOR_RPA, trigger.as(Actors.DOCUMENT, approval.reason()));
        rpa.dispatch(id);
    }

    /** Örn. {@code NON_COMPLIANT: UNIT_PRICE (Madde 4.1), PAYMENT_TERM güvenilmez}. */
    static String summary(ComplianceCompleted event) {
        if (event.findings().isEmpty()) {
            return event.result().name();
        }
        return event.result().name() + ": " + event.findings().stream()
                .map(f -> f.check().name() + (f.clauseNo() == null ? "" : " (Madde " + f.clauseNo() + ")")
                        + (f.reliable() ? "" : " güvenilmez"))
                .collect(Collectors.joining(", "));
    }
}
