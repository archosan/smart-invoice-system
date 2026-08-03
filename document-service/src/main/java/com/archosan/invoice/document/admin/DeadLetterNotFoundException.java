package com.archosan.invoice.document.admin;

import java.util.UUID;

public class DeadLetterNotFoundException extends RuntimeException {

    public DeadLetterNotFoundException(UUID id) {
        super("Park kaydı bulunamadı: " + id);
    }
}
