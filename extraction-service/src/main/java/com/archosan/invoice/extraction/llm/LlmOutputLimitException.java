package com.archosan.invoice.extraction.llm;

/**
 * LLM çıktısı bir sınıra dayandı; çıktı güvenilmez sayılır ve yeniden denenmez (B-29):
 * <ul>
 *   <li>İstem + çıktı bağlam penceresini ({@code num_ctx}) doldurdu: Ollama hata vermez, bağlamın başını (fatura
 *       metni dahil) atıp üretmeye devam eder; model kaynağı göremediği için değer uydurur (7. faturada görüldü).
 *       Aynı istem yine taşar.</li>
 *   <li>Çıktı üretim sınırında ({@code num_predict}) kesildi: yanıt yarımdır, model çoğunlukla tekrar döngüsüne
 *       girmiştir (prompt v2 ile 5. ve 6. faturada görüldü). Yarım çıktıyı geri beslemek istemi büyütür, düzeltmez.</li>
 * </ul>
 */
public class LlmOutputLimitException extends RuntimeException {

    private final String rawOutput;

    public LlmOutputLimitException(String message, String rawOutput) {
        super(message);
        this.rawOutput = rawOutput;
    }

    public String rawOutput() {
        return rawOutput;
    }
}
