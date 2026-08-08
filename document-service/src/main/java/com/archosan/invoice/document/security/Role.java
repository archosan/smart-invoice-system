package com.archosan.invoice.document.security;

/** API rolleri (B-43, DECISIONS.md §1 "Aktörler"). Spring Security'de {@code ROLE_<ad>} yetkisidir. */
public enum Role {

    /** Muhasebe uzmanı: yükler, düzeltir, düşük güvenli kaydı reddeder, mükerrer kararı verir. */
    EXPERT,
    /** Onaycı: onaya düşen faturayı onaylar ya da reddeder; kendi yüklediğini onaylayamaz. */
    APPROVER,
    /** Sistem yöneticisi: DLQ ve ayarlar. */
    ADMIN
}
