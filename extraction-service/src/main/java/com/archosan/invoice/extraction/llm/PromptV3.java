package com.archosan.invoice.extraction.llm;

/**
 * Prompt v3 (B-29): v1 + açıklamadaki sayıların miktar olmadığı. v2'nin konuma dayalı kuralı ("miktar birim fiyattan
 * hemen önceki sayıdır") PDF metninde sütun sırası her düzende korunmadığı için ters tepti: 6. faturada birim fiyat
 * miktar yazıldı, 5. faturada model döngüye girdi. v3 yalnız anlamı söyler, konum vermez.
 */
public final class PromptV3 implements InvoicePrompt {

    public static final String VERSION = "v3";

    static final PromptV3 INSTANCE = new PromptV3();

    static final String SYSTEM = PromptV1.SYSTEM + """

            Kalem miktarı:
            - quantity kalemin MİKTAR sütunudur: kaç adet, kg, saat vb. alındığı.
            - Açıklamanın içindeki sayılar (model kodu, ölçü, ağırlık, hacim; örn. "Rulman 6204 ZZ", "Hidrolik yağ
              20 L", "80 gr") açıklamanın parçasıdır: description'a olduğu gibi yaz, quantity olarak kullanma.
            """;

    private PromptV3() {
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String system() {
        return SYSTEM;
    }

    @Override
    public String user(String invoiceText) {
        return PromptV1.INSTANCE.user(invoiceText);
    }

    @Override
    public String retry(String error) {
        return PromptV1.INSTANCE.retry(error);
    }
}
