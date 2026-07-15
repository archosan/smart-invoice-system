package com.archosan.invoice.messaging.message;

/**
 * Sistemdeki tüm mesaj gövdelerinin ortak tipi. Liste kapalıdır: v2 yeni mesaj eklemez.
 */
public sealed interface InvoiceMessage
        permits ExtractInvoice, ExtractionCompleted, ExtractionFailed, CheckCompliance,
                ComplianceCompleted, PostToPortal, RpaCompleted, IngestContract {
}
