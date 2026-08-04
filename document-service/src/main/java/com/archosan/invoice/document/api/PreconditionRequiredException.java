package com.archosan.invoice.document.api;

/** {@code If-Match} gerekli ama yok; 428 döner (RFC 6585). */
class PreconditionRequiredException extends RuntimeException {

    PreconditionRequiredException() {
        super("If-Match zorunlu: önce GET ile kaydı alın, ETag'ini If-Match olarak gönderin");
    }
}
