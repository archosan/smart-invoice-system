package com.archosan.invoice.document.api;

import com.archosan.invoice.document.admin.DeadLetterAdmin;
import com.archosan.invoice.document.admin.DeadLetterNotFoundException;
import com.archosan.invoice.document.persistence.DeadLetterRepository;
import com.fasterxml.jackson.annotation.JsonRawValue;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Yönetici DLQ API'si (B-38, FR-A1, US-10): park edilmiş mesajları listeler, gösterir, yeniden işletir ya da yok
 * sayar. Yeniden işletme durum geçişiyledir (ADR-18), mesaj DLQ'dan taşınmaz. Yalnız {@code ADMIN} (B-43); geçişin
 * aktörü yöneticinin kullanıcı adıdır.
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
public class DeadLetterAdminController {

    private static final Set<String> STATUSES = Set.of("OPEN", "REPROCESSED", "IGNORED");

    private final DeadLetterRepository deadLetters;
    private final DeadLetterAdmin admin;

    public DeadLetterAdminController(DeadLetterRepository deadLetters, DeadLetterAdmin admin) {
        this.deadLetters = deadLetters;
        this.admin = admin;
    }

    /** Yeniden eskiye; {@code status} boşsa hepsi. */
    @GetMapping
    public PageResponse<DeadLetterRepository.Summary> list(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (status != null && !STATUSES.contains(status)) {
            throw new InvalidQueryException("status şunlardan biri olmalı: " + STATUSES);
        }
        if (page < 0) {
            throw new InvalidQueryException("page 0 veya daha büyük olmalı");
        }
        if (size < 1 || size > DocumentController.MAX_PAGE_SIZE) {
            throw new InvalidQueryException("size 1 ile " + DocumentController.MAX_PAGE_SIZE + " arasında olmalı");
        }
        return PageResponse.of(deadLetters.page(status, page, size), page, size, deadLetters.count(status));
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable UUID id) {
        DeadLetterRepository.Detail d = deadLetters.find(id).orElseThrow(() -> new DeadLetterNotFoundException(id));
        return new Detail(d.id(), d.kind(), d.sourceQueue(), d.messageType(), d.messageId(), d.documentId(),
                d.status(), d.createdAt(), d.body(), d.xDeath());
    }

    /** {@code RPA_FAILED → QUEUED_FOR_RPA} + yeni {@code PostToPortal}; durum uymazsa 409. */
    @PostMapping("/{id}/reprocess")
    public DeadLetterAdmin.Reprocessed reprocess(@PathVariable UUID id, Authentication authentication) {
        return admin.reprocess(id, authentication.getName());
    }

    @PostMapping("/{id}/ignore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ignore(@PathVariable UUID id) {
        admin.ignore(id);
    }

    /** Gövde ve {@code x-death} JSON olarak gömülü. */
    public record Detail(UUID id, String kind, String sourceQueue, String messageType, UUID messageId,
            UUID documentId, String status, OffsetDateTime createdAt, @JsonRawValue String body,
            @JsonRawValue String xDeath) {
    }
}
