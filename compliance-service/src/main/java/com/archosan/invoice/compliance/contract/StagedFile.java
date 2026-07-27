package com.archosan.invoice.compliance.contract;

import java.nio.file.Path;

/** Geçici dizine yazılmış, hash'i hesaplanmış yükleme. */
public record StagedFile(Path path, String sha256) {
}
