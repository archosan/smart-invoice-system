package com.archosan.invoice.document.review;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.document.events.ComplianceDispatch;
import com.archosan.invoice.document.events.DuplicateCheck;
import com.archosan.invoice.document.events.RpaDispatch;
import com.archosan.invoice.document.persistence.DocumentRepository;
import com.archosan.invoice.document.persistence.InvoiceDataRepository;
import com.archosan.invoice.document.security.Caller;
import com.archosan.invoice.document.security.Role;
import com.archosan.invoice.document.statemachine.StatusTransitions;
import com.archosan.invoice.document.statemachine.TransitionOutcome;
import com.archosan.invoice.document.statemachine.Trigger;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.RuleResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.archosan.invoice.document.DocumentStatus.COMPLIANCE_CHECKED;
import static com.archosan.invoice.document.DocumentStatus.DUPLICATE_SUSPECTED;
import static com.archosan.invoice.document.DocumentStatus.EXTRACTED;
import static com.archosan.invoice.document.DocumentStatus.NEEDS_REVIEW;
import static com.archosan.invoice.document.DocumentStatus.PENDING_APPROVAL;
import static com.archosan.invoice.document.DocumentStatus.QUEUED_FOR_RPA;
import static com.archosan.invoice.document.DocumentStatus.REJECTED;
import static com.archosan.invoice.document.DocumentStatus.VALIDATED;

/**
 * İnsan adımları (B-40, FR-D7, US-13), her biri tek transaction'da:
 * <ul>
 *   <li><b>Düzeltme:</b> {@code NEEDS_REVIEW → EXTRACTED} ({@code If-Match} sürümüyle), alanlar
 *       {@code invoice_data}'ya ({@code EXPERT_CORRECTION}) ve arama kopyalarına, sonra karar yeniden:
 *       {@code EXTRACTED → DUPLICATE_SUSPECTED} (uzman VKN'yi veya fatura no'yu değiştirmiş olabilir) ya da
 *       {@code VALIDATED} + {@code CheckCompliance}. Güven eşiği uygulanmaz (insan girdisi); düzeltme yapısal
 *       kontrolden ({@link CorrectionValidator}) geçmezse hiçbir şey değişmez.</li>
 *   <li><b>Onay:</b> {@code PENDING_APPROVAL → COMPLIANCE_CHECKED → QUEUED_FOR_RPA} + {@code PostToPortal}.</li>
 *   <li><b>Ret:</b> {@code NEEDS_REVIEW} (uzman) veya {@code PENDING_APPROVAL} (onaycı) → {@code REJECTED}, gerekçe
 *       zorunlu ve geçişin gerekçesi olarak kalır.</li>
 *   <li><b>Mükerrer kararı</b> (B-41): {@code DUPLICATE_SUSPECTED → REJECTED} (mükerrer, gerekçe zorunlu) ya da
 *       {@code → VALIDATED} + {@code CheckCompliance} (mükerrer değil, gerekçe isteğe bağlı). Portalda aynı kayıt
 *       varsa ön arama onu bulur ({@code FOUND_EXISTING}).</li>
 * </ul>
 * Geçişin aktörü isteği yapan kullanıcıdır (B-43). Uç bazlı rol kontrolü {@code SecurityConfiguration}'dadır;
 * burada durum bazlı olanlar: ret {@code NEEDS_REVIEW}'da uzman, {@code PENDING_APPROVAL}'da onaycı ister; onaycı
 * kendi yüklediği kaydı onaylayamaz (reddedebilir). Hata sırası: 404, 409 (durum), 403.
 */
@Service
public class DocumentReview {

    static final String DOCUMENT = "document-service";

    private final DocumentRepository documents;
    private final InvoiceDataRepository invoiceData;
    private final StatusTransitions transitions;
    private final CorrectionValidator validator;
    private final ComplianceDispatch compliance;
    private final DuplicateCheck duplicates;
    private final RpaDispatch rpa;
    private final TransactionTemplate transactions;

    public DocumentReview(DocumentRepository documents, InvoiceDataRepository invoiceData,
            StatusTransitions transitions, CorrectionValidator validator, ComplianceDispatch compliance,
            DuplicateCheck duplicates, RpaDispatch rpa, PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.invoiceData = invoiceData;
        this.transitions = transitions;
        this.validator = validator;
        this.compliance = compliance;
        this.duplicates = duplicates;
        this.rpa = rpa;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Hata sırası: 404, 409 (durum), 412 (sürüm), 422 (kurallar). */
    public void correct(UUID id, int expectedVersion, InvoiceFields fields, Caller caller) {
        transactions.executeWithoutResult(status -> {
            DocumentRepository.State state = require(id, NEEDS_REVIEW);
            if (state.version() != expectedVersion) {
                throw new VersionMismatchException(expectedVersion, state.version());
            }
            List<RuleResult> results = validator.validate(fields);
            List<RuleResult> violations = results.stream().filter(r -> !r.passed()).toList();
            if (!violations.isEmpty()) {
                throw new InvalidCorrectionException(violations);
            }

            Trigger trigger = Trigger.api("EXPERT_CORRECTION", caller.username(), null);
            if (transitions.apply(id, NEEDS_REVIEW, EXTRACTED, trigger, expectedVersion) == TransitionOutcome.STALE) {
                // Okuma ile koşullu UPDATE arasında başkası değiştirdi.
                throw new VersionMismatchException(expectedVersion, require(id, null).version());
            }
            invoiceData.save(id, fields, results, InvoiceDataRepository.Source.EXPERT_CORRECTION);
            documents.updateFieldCopies(id, fields);
            Optional<String> duplicate = duplicates.suspicion(id, fields);
            if (duplicate.isPresent()) {
                transitions.apply(id, EXTRACTED, DUPLICATE_SUSPECTED, trigger.as(DOCUMENT, duplicate.get()));
                return;
            }
            transitions.apply(id, EXTRACTED, VALIDATED, trigger.as(DOCUMENT, "uzman düzeltmesi, kurallar geçti"));
            compliance.dispatch(id, fields);
        });
    }

    /** Mükerrerse gerekçe zorunlu (400); kayıt {@code DUPLICATE_SUSPECTED} değilse 409. */
    public void decideDuplicate(UUID id, boolean duplicate, String reason, Caller caller) {
        boolean hasReason = reason != null && !reason.isBlank();
        if (duplicate && !hasReason) {
            throw new MissingReasonException();
        }
        transactions.executeWithoutResult(status -> {
            require(id, DUPLICATE_SUSPECTED);
            DocumentStatus to = duplicate ? REJECTED : VALIDATED;
            Trigger trigger = Trigger.api(duplicate ? "DUPLICATE_CONFIRMED" : "NOT_DUPLICATE", caller.username(),
                    hasReason ? reason.strip() : null);
            if (transitions.apply(id, DUPLICATE_SUSPECTED, to, trigger) == TransitionOutcome.STALE) {
                throw new ReviewConflictException("Kayıt artık DUPLICATE_SUSPECTED değil: " + id);
            }
            if (!duplicate) {
                compliance.dispatch(id, invoiceData.findFields(id).orElseThrow(
                        () -> new IllegalStateException("Mükerrer şüphesindeki kaydın invoice_data'sı yok: " + id)));
            }
        });
    }

    /** @return yeni {@code PostToPortal}'ın kimliği */
    public UUID approve(UUID id, Caller caller) {
        return transactions.execute(status -> {
            DocumentRepository.State state = require(id, PENDING_APPROVAL);
            if (caller.username().equals(state.uploadedBy())) {
                throw new ReviewForbiddenException("Onaycı kendi yüklediği faturayı onaylayamaz: " + caller.username());
            }
            Trigger trigger = Trigger.api("APPROVE", caller.username(), null);
            if (transitions.apply(id, PENDING_APPROVAL, COMPLIANCE_CHECKED, trigger) == TransitionOutcome.STALE) {
                throw new ReviewConflictException("Kayıt artık PENDING_APPROVAL değil: " + id);
            }
            transitions.apply(id, COMPLIANCE_CHECKED, QUEUED_FOR_RPA, trigger.as(DOCUMENT, null));
            return rpa.dispatch(id);
        });
    }

    public void reject(UUID id, String reason, Caller caller) {
        if (reason == null || reason.isBlank()) {
            throw new MissingReasonException();
        }
        transactions.executeWithoutResult(status -> {
            DocumentStatus from = require(id, null).status();
            Role needed = switch (from) {
                case NEEDS_REVIEW -> Role.EXPERT;
                case PENDING_APPROVAL -> Role.APPROVER;
                default -> throw new ReviewConflictException(
                        "Yalnız NEEDS_REVIEW veya PENDING_APPROVAL reddedilebilir, kayıt " + from);
            };
            if (!caller.has(needed)) {
                throw new ReviewForbiddenException(from + " kaydını reddetmek " + needed + " rolü ister");
            }
            if (transitions.apply(id, from, REJECTED, Trigger.api("REJECT", caller.username(), reason.strip()))
                    == TransitionOutcome.STALE) {
                throw new ReviewConflictException("Kayıt artık " + from + " değil: " + id);
            }
        });
    }

    /** @param expected {@code null} ise durum kontrol edilmez */
    private DocumentRepository.State require(UUID id, DocumentStatus expected) {
        DocumentRepository.State state = documents.findState(id)
                .orElseThrow(() -> new ReviewDocumentNotFoundException(id));
        if (expected != null && state.status() != expected) {
            throw new ReviewConflictException("Kayıt " + expected + " değil: " + state.status());
        }
        return state;
    }
}
