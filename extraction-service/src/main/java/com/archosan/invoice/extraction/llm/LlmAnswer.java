package com.archosan.invoice.extraction.llm;

/** Çözülmüş LLM çıktısı ve ham yanıt ({@code extraction_runs.raw_output} için). */
public record LlmAnswer(LlmInvoice invoice, String rawOutput) {
}
