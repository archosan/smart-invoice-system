package com.archosan.invoice.document.api;

import com.archosan.invoice.document.query.DocumentDetail;
import com.archosan.invoice.document.query.DocumentQueries;
import com.archosan.invoice.document.review.DocumentReview;
import com.archosan.invoice.document.security.Caller;
import com.archosan.invoice.messaging.message.InvoiceFields;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * İnsan adımları (B-40, FR-D7, US-13): alan düzeltme, onay, gerekçeli ret. Yanıt güncel kayıttır, sürümü
 * {@code ETag}'de. Roller {@code SecurityConfiguration}'da ve servisteki durum bazlı kontrollerde (B-43).
 */
@RestController
@RequestMapping("/api/v1/documents/{id}")
public class DocumentReviewController {

    private static final Pattern VERSION = Pattern.compile("\"?(\\d{1,9})\"?");

    private final DocumentReview review;
    private final DocumentQueries queries;

    public DocumentReviewController(DocumentReview review, DocumentQueries queries) {
        this.review = review;
        this.queries = queries;
    }

    /**
     * Tam alan seti eskisinin yerine geçer. {@code If-Match} zorunlu (yoksa 428, uymazsa 412); kayıt
     * {@code NEEDS_REVIEW} değilse 409, kurallardan geçmezse 422.
     */
    @PutMapping("/fields")
    public ResponseEntity<DocumentDetail> correct(@PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody InvoiceFields fields, Authentication authentication) {
        review.correct(id, version(ifMatch), fields, Caller.of(authentication));
        return current(id);
    }

    /** {@code PENDING_APPROVAL} değilse 409; onaycı kendi yüklediğini onaylayamaz (403). */
    @PostMapping("/approve")
    public ResponseEntity<DocumentDetail> approve(@PathVariable UUID id, Authentication authentication) {
        review.approve(id, Caller.of(authentication));
        return current(id);
    }

    /**
     * Gerekçe zorunlu (400); kayıt {@code NEEDS_REVIEW} veya {@code PENDING_APPROVAL} değilse 409. {@code NEEDS_REVIEW}
     * uzman, {@code PENDING_APPROVAL} onaycı ister (403).
     */
    @PostMapping("/reject")
    public ResponseEntity<DocumentDetail> reject(@PathVariable UUID id, @RequestBody RejectRequest request,
            Authentication authentication) {
        review.reject(id, request.reason(), Caller.of(authentication));
        return current(id);
    }

    public record RejectRequest(String reason) {
    }

    /**
     * Mükerrer şüphesini kapatır (B-41, US-03): {@code duplicate=true} → {@code REJECTED} (gerekçe zorunlu),
     * {@code false} → {@code VALIDATED} ve uyum kontrolü. {@code duplicate} yoksa 400, kayıt
     * {@code DUPLICATE_SUSPECTED} değilse 409.
     */
    @PostMapping("/duplicate-decision")
    public ResponseEntity<DocumentDetail> decideDuplicate(@PathVariable UUID id,
            @RequestBody DuplicateDecisionRequest request, Authentication authentication) {
        if (request.duplicate() == null) {
            throw new InvalidQueryException("duplicate alanı zorunlu: true (mükerrer) veya false (mükerrer değil)");
        }
        review.decideDuplicate(id, request.duplicate(), request.reason(), Caller.of(authentication));
        return current(id);
    }

    public record DuplicateDecisionRequest(Boolean duplicate, String reason) {
    }

    private ResponseEntity<DocumentDetail> current(UUID id) {
        return DocumentController.withETag(queries.findDetail(id).orElseThrow(() -> new DocumentNotFoundException(id)));
    }

    private static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        Matcher matcher = VERSION.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw new InvalidQueryException("If-Match kaydın sürümü olmalı (GET yanıtındaki ETag), örn. \"3\"");
        }
        return Integer.parseInt(matcher.group(1));
    }
}
