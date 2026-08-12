package com.archosan.invoice.document.statemachine;

public enum TransitionOutcome {

    /** Durum değişti, {@code status_transitions}'a yazıldı. */
    APPLIED,

    /**
     * Kayıt beklenen durumda (veya sürümde) değildi, hiçbir şey değişmedi. Mesaj tetikleyicisinde olay
     * {@code dead_letters}'a {@code LATE_EVENT} olarak yazılmıştır.
     */
    STALE
}
