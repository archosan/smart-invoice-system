package com.archosan.invoice.document.upload;

import com.archosan.invoice.document.DocumentStatus;
import com.archosan.invoice.document.persistence.DocumentRef;
import com.archosan.invoice.document.persistence.DocumentRepository;
import com.archosan.invoice.document.storage.DocumentStorage;
import com.archosan.invoice.document.storage.StagedFile;
import com.archosan.invoice.messaging.CorrelationScope;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Fatura yükleme (FR-D1, FR-D2, US-02; DECISIONS.md §4.1 "Yükleme isteğinin içi").
 *
 * <ol>
 *   <li>Dosya geçici dizine akış halinde yazılır, SHA-256 hesaplanır.</li>
 *   <li>Tek transaction: {@code INSERT … ON CONFLICT DO NOTHING}; yeniyse {@code status_transitions} ve
 *       {@code outbox(ExtractInvoice)} yazılır, dosya commit'ten önce yerine taşınır.</li>
 *   <li>Hash biliniyorsa mevcut kayıt döner, geçici dosya silinir.</li>
 * </ol>
 *
 * Taşıma commit'ten öncedir: commit ile taşıma arasında çökme {@code ExtractInvoice}'ı olmayan dosyaya
 * yönlendirirdi. Ters durumda yalnızca sahipsiz dosya kalır; gece temizliği siler (B-15).
 */
@Service
public class DocumentUploadService {

    static final String TRIGGER_UPLOAD = "UPLOAD";

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);

    private final DocumentStorage storage;
    private final DocumentRepository documents;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;

    public DocumentUploadService(DocumentStorage storage, DocumentRepository documents, OutboxWriter outbox,
            PlatformTransactionManager transactionManager) {
        this.storage = storage;
        this.documents = documents;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** @throws com.archosan.invoice.document.storage.NotPdfException içerik PDF değilse */
    /** @param uploadedBy yükleyen kullanıcı (B-43): {@code uploaded_by} ve ilk geçişin aktörü */
    public UploadResult upload(InputStream content, String uploadedBy) throws IOException {
        StagedFile staged = storage.stage(content);
        try {
            return transactions.execute(status -> store(staged, uploadedBy));
        } finally {
            storage.discard(staged);
        }
    }

    private UploadResult store(StagedFile staged, String uploadedBy) {
        UUID documentId = UUID.randomUUID();
        String storageUri = storage.uriFor(staged.sha256()).toString();

        if (!documents.insertIfAbsent(documentId, staged.sha256(), storageUri, uploadedBy)) {
            DocumentRef existing = documents.findBySha256(staged.sha256())
                    .orElseThrow(() -> new IllegalStateException("Çakışan kayıt bulunamadı: " + staged.sha256()));
            try (CorrelationScope ignored = CorrelationScope.open(existing.id())) {
                log.info("Aynı dosya daha önce yüklenmiş, mevcut kayıt dönülüyor: durum={}", existing.status());
            }
            return new UploadResult(existing.id(), existing.status(), true);
        }

        try (CorrelationScope ignored = CorrelationScope.open(documentId)) {
            documents.recordTransition(documentId, null, DocumentStatus.RECEIVED, TRIGGER_UPLOAD, null, uploadedBy,
                    null);
            outbox.add(documentId, new ExtractInvoice(documentId, storageUri, staged.sha256()));
            storage.promote(staged);
            log.info("Fatura alındı: sha256={}", staged.sha256());
        }
        return new UploadResult(documentId, DocumentStatus.RECEIVED, false);
    }
}
