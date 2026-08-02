package com.archosan.invoice.document.api;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.document.query.DocumentDetail;
import com.archosan.invoice.document.query.DocumentFilter;
import com.archosan.invoice.document.query.DocumentQueries;
import com.archosan.invoice.document.query.DocumentSummary;
import com.archosan.invoice.document.query.HistoryEntry;
import com.archosan.invoice.document.upload.DocumentUploadService;
import com.archosan.invoice.document.upload.UploadResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    static final int MAX_PAGE_SIZE = 100;

    private final DocumentUploadService uploads;
    private final DocumentQueries queries;

    public DocumentController(DocumentUploadService uploads, DocumentQueries queries) {
        this.uploads = uploads;
        this.queries = queries;
    }

    /** 202 yeni kayıt ({@code Location} ile), 200 aynı dosya daha önce yüklenmiş (US-02). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file,
            Authentication authentication) throws IOException {
        UploadResult result;
        try (InputStream content = file.getInputStream()) {
            result = uploads.upload(content, authentication.getName());
        }
        UploadResponse body = new UploadResponse(result.documentId(), result.status(), result.duplicate());
        if (result.duplicate()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.accepted()
                .location(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}").buildAndExpand(result.documentId()).toUri())
                .body(body);
    }

    /**
     * Durum, alanlar, kural sonuçları, uyum sonucu ve portal kayıt no (FR-D5). Sürüm {@code ETag}'dedir; alan
     * düzeltmesi onu {@code If-Match} olarak ister (B-40).
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDetail> get(@PathVariable UUID id) {
        return withETag(queries.findDetail(id).orElseThrow(() -> new DocumentNotFoundException(id)));
    }

    /** Durum geçmişi, eskiden yeniye (B-42, US-12); kayıt yoksa 404. Geçmiş yalnız eklenir (NFR-05). */
    @GetMapping("/{id}/history")
    public List<HistoryEntry> history(@PathVariable UUID id) {
        List<HistoryEntry> history = queries.history(id);
        if (history.isEmpty() && !queries.exists(id)) {
            throw new DocumentNotFoundException(id);
        }
        return history;
    }

    static ResponseEntity<DocumentDetail> withETag(DocumentDetail detail) {
        return ResponseEntity.ok().eTag("\"" + detail.version() + "\"").body(detail);
    }

    /**
     * Filtreli, sayfalı liste (FR-D5); yeniden eskiye. {@code from}/{@code to} fatura tarihine uygulanır, uçlar dahil;
     * {@code vkn} tam eşleşir.
     */
    @GetMapping
    public PageResponse<DocumentSummary> list(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) String vkn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new InvalidQueryException("page 0 veya daha büyük olmalı");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidQueryException("size 1 ile " + MAX_PAGE_SIZE + " arasında olmalı");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidQueryException("from, to'dan sonra olamaz");
        }
        DocumentFilter filter = new DocumentFilter(status, vkn == null || vkn.isBlank() ? null : vkn, from, to);
        List<DocumentSummary> content = queries.search(filter, page, size);
        return PageResponse.of(content, page, size, queries.count(filter));
    }
}
