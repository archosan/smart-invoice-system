package com.archosan.invoice.document.statemachine;

import com.archosan.invoice.document.DocumentStatus;

/**
 * Geçiş tabloda yok. Hedefi kod seçtiği için programlama hatasıdır; mesaj işlenirken atılırsa mesaj geri konur ve
 * teslim limitinden sonra DLQ'ya düşer.
 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(DocumentStatus from, DocumentStatus to) {
        super("Geçersiz durum geçişi: " + from + " → " + to);
    }
}
