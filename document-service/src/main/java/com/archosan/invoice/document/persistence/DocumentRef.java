package com.archosan.invoice.document.persistence;

import com.archosan.invoice.document.DocumentStatus;

import java.util.UUID;

/** Bir kaydın kimliği ve güncel durumu. */
public record DocumentRef(UUID id, DocumentStatus status) {
}
