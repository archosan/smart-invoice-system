package com.archosan.invoice.messaging.message;

/**
 * Bir doğrulama kuralının sonucu (FR-E3). {@code rule} bilinçli olarak string'dir: yeni kural eklemek
 * mesaj sözleşmesini değiştirmesin.
 */
public record RuleResult(String rule, boolean passed, String detail) {
}
