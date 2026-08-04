package com.archosan.invoice.document.api;

import java.util.List;

/** Offset sayfalama yanıtı; {@code page} 0'dan başlar. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        return new PageResponse<>(content, page, size, totalElements, (int) ((totalElements + size - 1) / size));
    }
}
