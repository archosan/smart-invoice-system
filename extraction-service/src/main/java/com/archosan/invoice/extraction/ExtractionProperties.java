package com.archosan.invoice.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;

/**
 * extraction-service ayarları ({@code invoice.extraction.*}).
 *
 * @param storageDir   PDF deposu; document-service'in volume'u salt okunur bağlanır (ADR-11). {@code storageUri}
 *                     bunun dışını gösteremez
 * @param minTextChars boşluk dışı karakter sayısı bunun altındaysa PDF metinsiz sayılır (taranmış sayfada kalan
 *                     damga, sayfa no gibi kırıntılar LLM'e gitmesin)
 * @param llm          LLM ve semafor ayarları (NFR-08, NFR-09)
 * @param validation   kural toleransları ve güven skoru ağırlıkları (FR-E3, FR-E4; kalibrasyon B-29, A5)
 * @param retention    ham LLM çıktısının saklama süresi (FR-E6, B-47)
 */
@ConfigurationProperties("invoice.extraction")
public record ExtractionProperties(
        @DefaultValue("/data/documents") Path storageDir,
        @DefaultValue("20") int minTextChars,
        @DefaultValue Llm llm,
        @DefaultValue Validation validation,
        @DefaultValue Retention retention) {

    /** Varsayılan doğrulama ayarlarıyla (testler için); tek kurucu kalsın ki ayar bağlama bozulmasın. */
    public static ExtractionProperties of(Path storageDir, int minTextChars, Llm llm) {
        return new ExtractionProperties(storageDir, minTextChars, llm, Validation.defaults(),
                new Retention(Duration.ofDays(90), "0 30 3 * * *"));
    }

    /**
     * @param rawOutput ham LLM yanıtı bu süreden eskiyse silinir, deneme satırı kalır
     * @param cron      temizlik zamanı (Spring cron); varsayılan her gece 03:30
     */
    public record Retention(
            @DefaultValue("90d") Duration rawOutput,
            @DefaultValue("0 30 3 * * *") String cron) {
    }

    /**
     * @param perLineTolerance  kalem başına yuvarlama payı; kalem toplamı ve KDV kurallarında tolerans
     *                          max({@code minimumTolerance}, kalem sayısı × bu)
     * @param minimumTolerance  ölçekli toleransın alt sınırı
     * @param totalTolerance    ara toplam + KDV = genel toplam için sabit tolerans (tek toplama)
     * @param weights           kalan her kuralın skordan düştüğü ağırlık
     */
    public record Validation(
            @DefaultValue("0.01") BigDecimal perLineTolerance,
            @DefaultValue("0.02") BigDecimal minimumTolerance,
            @DefaultValue("0.02") BigDecimal totalTolerance,
            @DefaultValue Weights weights) {

        public static Validation defaults() {
            return new Validation(new BigDecimal("0.01"), new BigDecimal("0.02"), new BigDecimal("0.02"),
                    Weights.defaults());
        }
    }

    /**
     * Güven skoru = 1,00 − kalan kuralların ağırlıkları toplamı, alt sınır 0 (B-20, A5 ilk sürüm). Kritik kurallar tek
     * başına skoru varsayılan eşiğin (0,80) altına iter; hafif kurallar (tarih sırası, KDV tutarlılığı) tek başına
     * itmez, ikisi birlikte iter.
     */
    public record Weights(
            @DefaultValue("0.50") BigDecimal requiredFieldsPresent,
            @DefaultValue("0.30") BigDecimal vkn10Digits,
            @DefaultValue("0.40") BigDecimal linesSumEqualsSubtotal,
            @DefaultValue("0.40") BigDecimal subtotalPlusVatEqualsTotal,
            @DefaultValue("0.15") BigDecimal invoiceDateNotAfterDueDate,
            @DefaultValue("0.30") BigDecimal amountsParsed,
            @DefaultValue("0.15") BigDecimal vatMatchesLines) {

        public static Weights defaults() {
            return new Weights(new BigDecimal("0.50"), new BigDecimal("0.30"), new BigDecimal("0.40"),
                    new BigDecimal("0.40"), new BigDecimal("0.15"), new BigDecimal("0.30"), new BigDecimal("0.15"));
        }
    }

    /**
     * @param model        Ollama sohbet modeli (B-18 K1; ölçüm B-29)
     * @param timeout      tek LLM çağrısının beklenen üst süresi; semafor kirası bunun iki katıdır
     * @param permits      servisler arası {@code sem:llm} izin sayısı (ADR-07)
     * @param localPermits Redis'e ulaşılamazken yerel semaforun izin sayısı; GPU'yu compliance de kullandığı için
     *                     temkinli
     * @param permitWait   izin için en fazla bekleme; aşılırsa mesaj geri konur
     * @param maxAttempts  bozuk çıktı ve zaman aşımı için toplam deneme (B-19); tükenince ExtractionFailed
     * @param availabilityProbeInterval Ollama'ya ulaşılamıyorken dinleyici durur, bu aralıkla yoklanır (A1)
     * @param numCtx       Ollama bağlam penceresi (token). Verilmezse Ollama makineye göre seçer (burada 4096): uzun
     *                     faturada çıktı yazılırken fatura metni bağlamdan düşer ve model değer uydurur (B-29)
     * @param maxOutputTokens üretilecek çıktının üst sınırı ({@code num_predict}). Yoksa tekrar döngüsüne giren model
     *                     durmaz; istemci zaman aşımıyla bağlantıyı kapatsa da Ollama üretmeye devam eder ve
     *                     arkasındaki istekleri bekletir (B-29). {@code numCtx}'ten küçük olmalı
     * @param promptVersion kullanılan prompt ({@code v1}, {@code v2}, {@code v3}); {@code extraction_runs} ve olaya yazılır
     */
    public record Llm(
            @DefaultValue("qwen2.5:7b-instruct") String model,
            @DefaultValue("120s") Duration timeout,
            @DefaultValue("2") int permits,
            @DefaultValue("1") int localPermits,
            @DefaultValue("10m") Duration permitWait,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("30s") Duration availabilityProbeInterval,
            @DefaultValue("8192") int numCtx,
            @DefaultValue("4096") int maxOutputTokens,
            @DefaultValue("v3") String promptVersion) {

        /**
         * Boş model adı açılışta reddedilir. {@code OLLAMA_CHAT_MODEL=} gibi boş bir ortam değişkeni
         * {@code ${…:varsayılan}}'ı devreye sokmaz; servis açılır, her çağrı Ollama'dan önce Spring AI'da düşer ve
         * her fatura teslim limitinden sonra DLQ'ya gider (B-23 duman testinde bulundu).
         */
        public Llm {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException(
                        "invoice.extraction.llm.model boş (OLLAMA_CHAT_MODEL boş mu?); Ollama sohbet modeli gerekli");
            }
            if (numCtx < 2048) {
                throw new IllegalArgumentException("invoice.extraction.llm.num-ctx en az 2048 olmalı: " + numCtx);
            }
            if (maxOutputTokens < 512 || maxOutputTokens >= numCtx) {
                throw new IllegalArgumentException("invoice.extraction.llm.max-output-tokens 512 ile num-ctx ("
                        + numCtx + ") arasında olmalı: " + maxOutputTokens);
            }
        }
    }
}
