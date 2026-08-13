package com.archosan.invoice.document.upload;

import com.archosan.invoice.document.DocumentStatus;

import java.util.UUID;

/** @param duplicate hash daha önce görülmüşse {@code true}; kayıt mevcut olandır (US-02) */
public record UploadResult(UUID documentId, DocumentStatus status, boolean duplicate) {
}
