package com.archosan.invoice.extraction.llm;

import java.time.Duration;

/**
 * Tek bir LLM denemesinin izi; {@code extraction_runs} satırı olur (B-21).
 *
 * @param rawOutput ham yanıt; zaman aşımında {@code null}
 * @param error     bozuk çıktı veya zaman aşımı açıklaması; başarılı denemede {@code null}
 */
public record LlmAttempt(int attemptNo, Duration duration, String rawOutput, String error) {
}
