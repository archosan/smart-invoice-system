package com.archosan.invoice.extraction.llm;

/**
 * Prompt v2 (B-29): v1 + kalem satırının sütun sırası. 2. faturayı düzeltti ama konuma dayalı kural ters tepti
 * (6. faturada birim fiyat miktar yazıldı, 5. faturada {@code vatRate} tekrar döngüsü); yerine {@link PromptV3}.
 * Karşılaştırma için kodda kalır.
 */
public final class PromptV2 implements InvoicePrompt {

    public static final String VERSION = "v2";

    static final PromptV2 INSTANCE = new PromptV2();

    static final String SYSTEM = PromptV1.SYSTEM + """

            Kalem satırları:
            - Bir kalem satırı sırasıyla şu sütunlardan oluşur: sıra no, açıklama, miktar, birim fiyat, KDV oranı,
              tutar. Satır başındaki sıra no miktar değildir.
            - quantity, birim fiyattan HEMEN ÖNCEKİ sayıdır.
            - Açıklamanın içindeki sayılar ve birimler (model kodu, ölçü, ağırlık, hacim; örn. "6204 ZZ", "20 L",
              "80 gr") açıklamaya aittir: description'a olduğu gibi yaz, quantity olarak kullanma.
            """;

    private PromptV2() {
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
