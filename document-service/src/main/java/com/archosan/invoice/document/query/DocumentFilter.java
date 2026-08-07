package com.archosan.invoice.document.query;

import com.archosan.invoice.document.DocumentStatus;

import java.time.LocalDate;

/**
 * Liste filtresi (FR-D5); boş alan filtre uygulanmaz demektir.
 *
 * @param supplierVkn tam eşleşme
 * @param from        fatura tarihi alt sınırı, dahil
 * @param to          fatura tarihi üst sınırı, dahil
 */
public record DocumentFilter(DocumentStatus status, String supplierVkn, LocalDate from, LocalDate to) {
}
