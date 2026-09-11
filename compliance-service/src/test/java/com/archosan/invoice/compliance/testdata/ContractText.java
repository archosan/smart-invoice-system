package com.archosan.invoice.compliance.testdata;

import com.archosan.invoice.compliance.testdata.SyntheticContract.Price;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sözleşmenin metni, madde madde (B-44). Madde başlıkları B-45'in chunking kalıbına uyar: {@code MADDE n – BAŞLIK},
 * alt maddeli düzende {@code n.m. …}. Fiyatlar {@code 1.840,00 TL}, tarihler {@code 01.01.2026} biçimindedir.
 */
final class ContractText {

    /** Satır: başlık, paragraf (madde metni) ya da girintili alt madde/kalem. */
    sealed interface Block permits Heading, Paragraph, Item {
    }

    record Heading(String text) implements Block {
    }

    record Paragraph(String text) implements Block {
    }

    record Item(String text) implements Block {
    }

    static final String TITLE = "ÇERÇEVE TEDARİK SÖZLEŞMESİ";
    static final String BUYER = "Archosan Ticaret A.Ş.";
    static final String BUYER_VKN = "1112223334";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String[] ONES = {"", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz"};
    private static final String[] TENS = {"", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen",
            "doksan"};

    private ContractText() {
    }

    static List<Block> of(SyntheticContract c) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Heading(TITLE));
        blocks.add(new Paragraph("Sözleşme tarihi: " + date(c.validFrom()) + " · Sözleşme no: "
                + c.file().replace(".pdf", "").toUpperCase(Locale.ROOT)));

        blocks.add(new Heading("MADDE 1 – TARAFLAR"));
        blocks.add(new Paragraph("Bu sözleşme, " + BUYER + " (VKN " + BUYER_VKN + ", bundan sonra \"Alıcı\") ile "
                + c.supplierName() + " (VKN " + c.supplierVkn() + ", bundan sonra \"Tedarikçi\") arasında "
                + "aşağıdaki koşullarla akdedilmiştir."));

        blocks.add(new Heading("MADDE 2 – KONU"));
        blocks.add(new Paragraph("Sözleşmenin konusu, Tedarikçinin Madde 4'te sayılan mal ve hizmetleri Alıcının "
                + "siparişleri üzerine teslim etmesi ve bunların bedelinin Madde 5'teki koşullarla ödenmesidir. "
                + "Sipariş miktarları her siparişte ayrıca belirlenir; bu sözleşme asgari alım taahhüdü içermez."));

        blocks.add(new Heading("MADDE 3 – SÜRE"));
        blocks.add(new Paragraph("Sözleşme " + date(c.validFrom()) + " tarihinde yürürlüğe girer ve "
                + date(c.validTo()) + " tarihinde kendiliğinden sona erer. Bu tarihler arasında düzenlenen "
                + "faturalar bu sözleşmeye tabidir."));

        blocks.add(new Heading("MADDE 4 – FİYATLAR"));
        if (c.layout().subClauses()) {
            for (Price p : c.prices()) {
                blocks.add(new Item(p.clauseNo() + ". " + p.description() + " için birim fiyat, " + p.unit()
                        + " başına " + money(p.unitPrice()) + " TL'dir (KDV hariç)."));
            }
            blocks.add(new Paragraph("Fiyatlar sözleşme süresince sabittir; fiyat değişikliği ancak yazılı ek "
                    + "protokolle yapılabilir."));
        } else {
            blocks.add(new Paragraph("Aşağıdaki birim fiyatlar KDV hariçtir ve sözleşme süresince sabittir. "
                    + "Faturada bu fiyatların üzerinde bir birim fiyat uygulanamaz."));
            for (Price p : c.prices()) {
                blocks.add(new Item("• " + p.description() + ": " + money(p.unitPrice()) + " TL / " + p.unit()));
            }
        }

        blocks.add(new Heading("MADDE 5 – ÖDEME KOŞULLARI"));
        String term = "fatura tarihinden itibaren " + c.paymentTermDays() + " (" + words(c.paymentTermDays())
                + ") gün içinde";
        if (c.layout().subClauses()) {
            blocks.add(new Item("5.1. Fatura bedeli " + term + " Tedarikçinin bildirdiği banka hesabına ödenir."));
            blocks.add(new Item("5.2. Vadesinde ödenmeyen tutara, vade tarihinden itibaren yasal gecikme faizi "
                    + "uygulanır."));
            blocks.add(new Item("5.3. Faturada sözleşmeye aykırı fiyat ya da vade bulunması halinde Alıcı "
                    + "faturayı itirazla iade edebilir."));
        } else {
            blocks.add(new Paragraph("Fatura bedeli " + term + " Tedarikçinin bildirdiği banka hesabına ödenir. "
                    + "Vadesinde ödenmeyen tutara yasal gecikme faizi uygulanır."));
        }

        blocks.add(new Heading("MADDE 6 – TESLİMAT"));
        blocks.add(new Paragraph("Teslimat, siparişte belirtilen adrese ve tarihte yapılır. Teslimat masrafları "
                + "aksi kararlaştırılmadıkça Tedarikçiye aittir; mal, teslim tutanağının imzalanmasıyla Alıcıya "
                + "geçer."));

        if (c.layout().longClause()) {
            blocks.add(new Heading("MADDE 7 – CEZAİ ŞART VE GECİKME"));
            longPenaltyClause().forEach(text -> blocks.add(new Paragraph(text)));
            blocks.add(new Heading("MADDE 8 – KALİTE VE İADE"));
            blocks.add(new Paragraph("Teslim edilen ürünler ilgili mevzuata ve ürünün teknik şartnamesine uygun "
                    + "olmalıdır. Ayıplı ürün, tespitinden itibaren on iş günü içinde Tedarikçiye bildirilir; "
                    + "Tedarikçi ayıplı ürünü kendi masrafıyla değiştirmekle yükümlüdür. Sterilizasyon belgesi "
                    + "bulunmayan medikal sarf malzemesi teslim alınmaz."));
            blocks.add(new Heading("MADDE 9 – GİZLİLİK"));
            blocks.add(new Paragraph("Taraflar, sözleşmenin ifası sırasında öğrendikleri ticari sırları ve kişisel "
                    + "verileri sözleşme süresince ve sona ermesinden sonra beş yıl boyunca gizli tutar; kişisel "
                    + "veriler yalnızca sözleşmenin ifası amacıyla işlenir."));
            blocks.add(new Heading("MADDE 10 – MÜCBİR SEBEP"));
            blocks.add(new Paragraph("Doğal afet, salgın hastalık, savaş, genel grev ve benzeri tarafların kontrolü "
                    + "dışındaki olaylar mücbir sebep sayılır. Mücbir sebep süresince yükümlülükler askıya alınır; "
                    + "mücbir sebep altmış günü aşarsa taraflardan her biri sözleşmeyi feshedebilir."));
            blocks.add(new Heading("MADDE 11 – FESİH VE UYUŞMAZLIK"));
        } else {
            blocks.add(new Heading("MADDE 7 – FESİH VE UYUŞMAZLIK"));
        }
        blocks.add(new Paragraph("Taraflardan biri yükümlülüklerini ağır biçimde ihlal ederse diğer taraf yazılı "
                + "bildirimle sözleşmeyi feshedebilir. Uyuşmazlıklarda İstanbul (Çağlayan) mahkemeleri ve icra "
                + "daireleri yetkilidir."));
        blocks.add(new Paragraph("Alıcı adına: " + BUYER + "            Tedarikçi adına: " + c.supplierName()));
        return blocks;
    }

    /**
     * Cezai şart maddesi: tek başına ~500 token'ı aşar ve alt madde numarası yoktur (fıkralar numarasız); B-45 onu
     * paragraf sınırından bölüp her parçaya madde başlığını ekler.
     */
    static List<String> longPenaltyClause() {
        return List.of(
                "Tedarikçi, siparişte belirtilen teslim tarihine uymakla yükümlüdür. Teslimatın gecikmesi "
                        + "halinde Tedarikçi, geciken her takvim günü için geciken teslimatın KDV hariç bedelinin "
                        + "binde beşi oranında gecikme cezası öder. Gecikme cezasının toplamı, ilgili siparişin KDV "
                        + "hariç bedelinin yüzde onunu geçemez. Gecikme cezası, Alıcının ayrıca uğradığı zararın "
                        + "tazminini istemesine engel değildir.",
                "Gecikme on beş takvim gününü aşarsa Alıcı, gecikme cezasını talep etmekle birlikte siparişi "
                        + "kısmen ya da tamamen iptal edebilir ve iptal edilen kısmı üçüncü kişilerden temin "
                        + "edebilir. Bu durumda üçüncü kişiden temin edilen malın bedeli ile sözleşme fiyatı "
                        + "arasındaki fark Tedarikçiden tahsil edilir. Alıcı, fark bedelini Tedarikçinin muaccel "
                        + "alacaklarından mahsup etme hakkına sahiptir.",
                "Teslim edilen ürünlerin teknik şartnameye aykırı olduğunun tespiti halinde, aykırılık "
                        + "giderilinceye kadar geçen süre gecikme sayılır ve birinci fıkradaki oran uygulanır. Aynı sipariş "
                        + "dönemi içinde üçüncü kez tekrarlanan aykırılık, sözleşmenin ağır ihlali sayılır ve "
                        + "Alıcıya Madde 11'de düzenlenen fesih hakkını verir. Bu halde Tedarikçi, sözleşmenin "
                        + "kalan süresi için öngörülen tahmini sipariş bedelinin yüzde beşi oranında cezai şart "
                        + "öder.",
                "Cezai şart ve gecikme cezası, Alıcı tarafından yazılı olarak bildirilir ve bildirimden "
                        + "itibaren otuz gün içinde ödenir. Tedarikçi, gecikmenin mücbir sebepten ya da Alıcının "
                        + "kusurundan kaynaklandığını belgelerle ispat ederse ceza uygulanmaz. Mücbir sebep "
                        + "bildirimi, olayın öğrenilmesinden itibaren beş iş günü içinde yapılmazsa bu savunmaya "
                        + "dayanılamaz.",
                "Tedarikçi, sözleşmenin imzasından itibaren on beş gün içinde, yıllık tahmini sipariş bedelinin "
                        + "yüzde üçü tutarında kesin ve süresiz banka teminat mektubu verir. Bu maddeden doğan ve "
                        + "bildirim süresi içinde ödenmeyen cezalar, Alıcı tarafından teminat mektubundan tahsil "
                        + "edilebilir; tahsilat halinde Tedarikçi teminatı on gün içinde eski tutarına tamamlar. "
                        + "Teminat, sözleşmenin sona ermesinden ve bütün cezaların ödenmesinden sonra iade edilir.",
                "Tarafların bu madde kapsamındaki hak ve yükümlülükleri, sözleşmenin herhangi bir nedenle "
                        + "sona ermesinden sonra da, sona erme tarihinden önce doğmuş olan cezalar bakımından "
                        + "yürürlükte kalır. Cezai şartın fahiş olduğu iddiası, Türk Borçlar Kanunu hükümleri "
                        + "saklı kalmak kaydıyla, tacir sıfatıyla kabul edilmiş olması nedeniyle ileri "
                        + "sürülemez.");
    }

    static String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.00", symbols).format(amount);
    }

    static String date(LocalDate date) {
        return DATE.format(date);
    }

    /** 1–999 yazıyla ("kırk iki", "yüz yirmi"). */
    static String words(int days) {
        if (days < 1 || days > 999) {
            throw new IllegalArgumentException("Yazıyla gün 1–999: " + days);
        }
        String hundreds = days >= 100 ? (days / 100 == 1 ? "yüz" : ONES[days / 100] + " yüz") : "";
        String rest = (TENS[days % 100 / 10] + " " + ONES[days % 10]).strip();
        return (hundreds + " " + rest).strip();
    }
}
