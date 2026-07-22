package com.archosan.invoice.compliance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/**
 * compliance-service ayarları ({@code invoice.compliance.*}).
 *
 * @param storageDir sözleşme PDF'lerinin içerik adresli saklandığı dizin (kendi volume'u)
 * @param embedding  embedding modeli
 * @param ingest     indeksleme sınırları (FR-C1, B-45)
 * @param llm        maddeden değer çıkarımı (FR-C2, B-46)
 * @param retrieval  filtreli vektör arama (FR-C2, B-46)
 */
@ConfigurationProperties("invoice.compliance")
public record ComplianceProperties(
        @DefaultValue("/data/contracts") Path storageDir,
        @DefaultValue Embedding embedding,
        @DefaultValue Ingest ingest,
        @DefaultValue Llm llm,
        @DefaultValue Retrieval retrieval) {

    /**
     * @param model      Ollama model adı; her chunk satırına yazılır
     * @param dimensions beklenen boyut ({@code vector(1024)}); farklı boyut kalıcı hatadır
     * @param batchSize  tek istekte gönderilen chunk sayısı
     */
    public record Embedding(
            @DefaultValue("bge-m3") String model,
            @DefaultValue("1024") int dimensions,
            @DefaultValue("16") int batchSize) {
    }

    /**
     * Sohbet modeli; semafor ayarları extraction ile aynı anlamdadır (ADR-07, {@code llm-support}).
     *
     * @param model           Ollama sohbet modeli (extraction ile aynı)
     * @param timeout         tek çağrının okuma zaman aşımı; izin kirası bunun iki katı
     * @param permits         servisler arası toplam eşzamanlı LLM çağrısı ({@code sem:llm})
     * @param localPermits    Redis yokken bu örneğin sınırı
     * @param permitWait      izin için en uzun bekleme
     * @param numCtx          bağlam penceresi (4 madde + soru rahat sığar)
     * @param maxOutputTokens çıktı üst sınırı (tek değer + alıntı)
     */
    public record Llm(
            @DefaultValue("qwen2.5:7b-instruct") String model,
            @DefaultValue("120s") Duration timeout,
            @DefaultValue("2") int permits,
            @DefaultValue("1") int localPermits,
            @DefaultValue("10m") Duration permitWait,
            @DefaultValue("4096") int numCtx,
            @DefaultValue("512") int maxOutputTokens) {
    }

    /** @param topK kontrol başına getirilen chunk sayısı ({@code LIMIT 4}, §4.3) */
    public record Retrieval(@DefaultValue("4") int topK) {
    }

    /**
     * Sınırlar karakter cinsindendir: ~4 karakter = 1 token (Türkçe metin için kaba ama yeterli; bge-m3'ün penceresi
     * 8192 token, kesinlik gerekmez).
     *
     * @param minTextChars    toplam metin bundan kısaysa sözleşme metinsiz sayılır ({@code FAILED})
     * @param maxClauseChars  bunu aşan madde paragraf sınırından bölünür (~500 token)
     * @param windowChars     madde kalıbı bulunamazsa pencere boyu (~400 token)
     * @param overlapChars    pencereler arası örtüşme (~50 token)
     */
    public record Ingest(
            @DefaultValue("20") int minTextChars,
            @DefaultValue("2000") int maxClauseChars,
            @DefaultValue("1600") int windowChars,
            @DefaultValue("200") int overlapChars) {
    }
}
