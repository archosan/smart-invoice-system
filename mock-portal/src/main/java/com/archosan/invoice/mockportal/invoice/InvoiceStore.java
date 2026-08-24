package com.archosan.invoice.mockportal.invoice;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Portalın kendi fatura kayıtları, bellekte (A6). Portal sistemin parçası değildir; yeniden başlayınca kayıtlar
 * silinir. Eski portal gibi idempotent değildir: aynı fatura iki kez girilirse iki kayıt oluşur (NFR-01'in nedeni).
 */
@Component
public class InvoiceStore {

    private static final long FIRST_REF_NO = 1000;

    private final List<PortalInvoice> invoices = new ArrayList<>();
    private long nextRefNo = FIRST_REF_NO;

    public synchronized PortalInvoice add(InvoiceForm.Valid form) {
        PortalInvoice invoice = new PortalInvoice(String.valueOf(nextRefNo++), form.supplierVkn(),
                form.supplierName(), form.invoiceNo(), form.invoiceDate(), form.dueDate(), form.grandTotal(),
                form.vatTotal(), form.currency(), Instant.now());
        invoices.add(invoice);
        return invoice;
    }

    public synchronized Optional<PortalInvoice> find(String refNo) {
        return invoices.stream().filter(i -> i.refNo().equals(refNo)).findFirst();
    }

    /**
     * En yeni kayıt önce. {@code query} boş değilse fatura numarasında büyük/küçük harf duyarsız arar.
     *
     * @param page 0'dan; aralık dışıysa son sayfaya (boşsa 0'a) çekilir
     */
    public synchronized InvoicePage page(String query, int page, int size) {
        String stripped = query == null ? "" : query.strip();
        String needle = stripped.toLowerCase(Locale.ROOT);
        List<PortalInvoice> matching = invoices.reversed().stream()
                .filter(i -> needle.isEmpty() || i.invoiceNo().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        int totalPages = Math.max(1, (matching.size() + size - 1) / size);
        int current = Math.clamp(page, 0, totalPages - 1);
        int from = current * size;
        List<PortalInvoice> items = matching.subList(from, Math.min(from + size, matching.size()));
        return new InvoicePage(List.copyOf(items), stripped, current, totalPages, matching.size());
    }

    /** Bir liste sayfası; {@code page} 0'dan, ekranda 1'den gösterilir. */
    public record InvoicePage(List<PortalInvoice> items, String query, int page, int totalPages, int totalElements) {

        public boolean hasPrevious() {
            return page > 0;
        }

        public boolean hasNext() {
            return page < totalPages - 1;
        }
    }
}
