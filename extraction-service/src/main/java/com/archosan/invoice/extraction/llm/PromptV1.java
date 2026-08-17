package com.archosan.invoice.extraction.llm;

/**
 * Prompt v1 (B-18). B-29'daki ölçümde 2. faturada açıklamadaki sayıları ("Rulman 6204 ZZ", "Hidrolik yağ 20 L")
 * miktar sandı; {@link PromptV2} bunu düzeltir. Karşılaştırma için kodda kalır.
 */
public final class PromptV1 implements InvoicePrompt {

    public static final String VERSION = "v1";

    static final PromptV1 INSTANCE = new PromptV1();

    static final String SYSTEM = """
            Sen Türkçe tedarikçi faturalarından alan çıkaran bir asistansın. Sana PDF'ten çıkarılmış düz metin
            verilecek. Yalnızca şemadaki JSON nesnesini döndür.

            Kurallar:
            - Değerleri faturada BASILDIĞI GİBİ yaz; sayıları ve tarihleri dönüştürme, yuvarlama, hesaplama yapma.
              Örnek: "1.234,56 TL" -> "1.234,56 TL"; "18/09/2026" -> "18/09/2026"; "%20" -> "%20".
            - supplierName ve supplierVkn faturayı KESEN firmaya (satıcı) aittir. "SAYIN" veya "Alıcı" bloğundaki
              firma ve VKN alıcıdır, onları kullanma.
            - supplierVkn yalnızca VKN'dir; ETTN, MERSİS no, IBAN, vergi dairesi, fatura no VKN değildir.
            - lines faturadaki her kalemdir: açıklama, miktar, birim fiyat, KDV oranı. Sayfalara bölünmüş kalemleri
              birleştir, hiçbir kalemi atlama.
            - subtotal ara toplam, vatTotal KDV toplamı, grandTotal genel toplamdır; oran başına KDV satırlarını
              vatTotal yerine kullanma.
            - currency faturadaki para birimi yazısı veya simgesidir (örn. "TL", "₺").
            - Faturada bulunmayan alan için boş string yaz.
            """;

    private PromptV1() {
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
        return "Fatura metni:\n<<<\n" + invoiceText + "\n>>>";
    }

    @Override
    public String retry(String error) {
        return "Önceki yanıtın geçerli değildi: " + error
                + "\nYalnızca şemaya uygun tek bir JSON nesnesi döndür; açıklama veya başka metin ekleme.";
    }
}
