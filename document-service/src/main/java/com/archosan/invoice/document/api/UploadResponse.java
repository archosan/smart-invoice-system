package com.archosan.invoice.document.api;

import com.archosan.invoice.document.DocumentStatus;

import java.util.UUID;

/** {@code POST /api/v1/documents} yanıtı; 202'de {@code duplicate=false}, 200'de {@code true}. */
public record UploadResponse(UUID documentId, DocumentStatus status, boolean duplicate) {
}
