package com.archosan.invoice.document.events;

import com.archosan.invoice.document.persistence.DocumentRepository;
import com.archosan.invoice.messaging.message.InvoiceFields;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * İçerik bazlı mükerrer şüphesi (B-41, FR-D8, US-03): aynı VKN + fatura no'lu, reddedilmemiş başka bir kayıt varsa
 * şüphe. Öncelik kuralında güven kontrolünden sonra, {@code VALIDATED}'dan önce değerlendirilir; çıkarım sonrası karar
 * ve uzman düzeltmesi aynı yoldan geçer. Çağıranın transaction'ında; aynı faturanın eşzamanlı belgeleri danışma
 * kilidiyle sıraya girer, ikincisi birincinin commit'ini görür. Kaydın kendi alanları bundan önce yazılmış olmalıdır.
 */
@Component
public class DuplicateCheck {

    private final DocumentRepository documents;

    public DuplicateCheck(DocumentRepository documents) {
        this.documents = documents;
    }

    /** @return şüphe varsa geçişin gerekçesi; VKN veya fatura no yoksa kontrol yapılmaz */
    public Optional<String> suspicion(UUID documentId, InvoiceFields fields) {
        if (fields == null || isBlank(fields.supplierVkn()) || isBlank(fields.invoiceNo())) {
            return Optional.empty();
        }
        documents.lockInvoiceKey(fields.supplierVkn(), fields.invoiceNo());
        List<DocumentRepository.Match> matches =
                documents.findSameInvoice(documentId, fields.supplierVkn(), fields.invoiceNo());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("mükerrer şüphesi: " + matches.stream()
                .map(m -> m.id() + " (" + m.status() + ")")
                .collect(Collectors.joining(", ")));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
