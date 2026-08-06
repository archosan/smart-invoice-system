package com.archosan.invoice.document.events;

import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@code CheckCompliance}'ı alanlardan kurup outbox'a yazar. Çağıranın transaction'ında; kayıt bu sırada
 * {@code VALIDATED}'a geçmiş olmalıdır. Çıkarım sonrası karar ve uzman düzeltmesi (B-40) aynı yoldan geçer.
 */
@Component
public class ComplianceDispatch {

    private final OutboxWriter outbox;

    public ComplianceDispatch(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void dispatch(UUID documentId, InvoiceFields fields) {
        outbox.add(documentId, new CheckCompliance(documentId, fields.supplierVkn(), fields.invoiceDate(),
                fields.dueDate(), fields.lines(), fields.subtotal(), fields.vatTotal(), fields.grandTotal(),
                fields.currency()));
    }
}
