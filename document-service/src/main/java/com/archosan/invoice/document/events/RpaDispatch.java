package com.archosan.invoice.document.events;

import com.archosan.invoice.document.persistence.DocumentRepository;
import com.archosan.invoice.document.persistence.InvoiceDataRepository;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@code PostToPortal}'ı çıkarılmış alanlardan kurup outbox'a yazar ve kimliğini bekleyen komut olarak saklar (B-38,
 * A4). Çağıranın transaction'ında; kayıt bu sırada {@code QUEUED_FOR_RPA}'ya geçmiş olmalıdır. Normal akış (uyum
 * sonrası) ve yöneticinin yeniden işletmesi aynı yoldan geçer.
 */
@Component
public class RpaDispatch {

    private final InvoiceDataRepository invoiceData;
    private final DocumentRepository documents;
    private final OutboxWriter outbox;

    public RpaDispatch(InvoiceDataRepository invoiceData, DocumentRepository documents, OutboxWriter outbox) {
        this.invoiceData = invoiceData;
        this.documents = documents;
        this.outbox = outbox;
    }

    /** @return yeni komutun {@code messageId}'si */
    public UUID dispatch(UUID documentId) {
        InvoiceFields fields = invoiceData.findFields(documentId).orElseThrow(
                () -> new IllegalStateException("Portala gidecek kaydın invoice_data'sı yok: " + documentId));
        UUID commandId = outbox.add(documentId, new PostToPortal(documentId, fields.supplierVkn(),
                fields.supplierName(), fields.invoiceNo(), fields.invoiceDate(), fields.dueDate(), fields.grandTotal(),
                fields.vatTotal(), fields.currency()));
        documents.setPendingRpaCommand(documentId, commandId);
        return commandId;
    }
}
