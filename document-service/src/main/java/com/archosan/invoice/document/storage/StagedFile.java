package com.archosan.invoice.document.storage;

import java.nio.file.Path;

/** Geçici dizine yazılmış, hash'i hesaplanmış ama henüz yerine taşınmamış dosya. */
public record StagedFile(Path path, String sha256) {
}
