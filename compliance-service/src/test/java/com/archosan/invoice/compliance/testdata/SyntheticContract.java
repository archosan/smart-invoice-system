package com.archosan.invoice.compliance.testdata;

import com.archosan.invoice.messaging.message.ComplianceCompleted;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sentetik sözleşme senaryosu (B-44). Değerler sözleşmenin üzerinde <b>yazılı olanlardır</b>; B-45'in indekslemesi ve
 * B-46'nın değer çıkarımı bunlarla sınanır. Sözleşmeler B-16 faturalarının tedarikçilerine yazılır; {@code expectation}
 * ilgili faturanın uyum sonucunu söyler.
 *
 * @param file             PDF dosya adı
 * @param scenario         neyi sınadığı
 * @param supplierName     tedarikçi unvanı
 * @param supplierVkn      tedarikçi VKN'si (fatura ile aynı)
 * @param validFrom        geçerlilik başlangıcı (dahil)
 * @param validTo          geçerlilik bitişi (dahil)
 * @param paymentTermDays  ödeme vadesi, fatura tarihinden itibaren gün
 * @param prices           fiyat maddesindeki kalemler (KDV hariç birim fiyat)
 * @param layout           çizim seçenekleri
 * @param expectation      indeksleme ve ilgili faturanın uyum beklentisi
 * @param note             açıklama
 */
public record SyntheticContract(
        String file,
        String scenario,
        String supplierName,
        String supplierVkn,
        LocalDate validFrom,
        LocalDate validTo,
        int paymentTermDays,
        List<Price> prices,
        Layout layout,
        Expectation expectation,
        String note) {

    public SyntheticContract {
        prices = List.copyOf(prices);
    }

    /** İndeksleme FAILED; uyum beklentisi yok (metinsiz sözleşme hiç seçilmez). */
    SyntheticContract failing() {
        return new SyntheticContract(file, scenario, supplierName, supplierVkn, validFrom, validTo, paymentTermDays,
                prices, layout, new Expectation(Ingest.FAILED, expectation.invoiceFile(), null, null,
                        expectation.paymentClauseNo()), note);
    }

    /**
     * Fiyat maddesindeki bir kalem.
     *
     * @param description faturadaki kalem açıklamasının başı (fatura satırı bununla başlar)
     * @param unitPrice   KDV hariç birim fiyat
     * @param unit        birim (adet, kg, koli…)
     * @param clauseNo    kalemin yazılı olduğu madde numarası ("4" ya da alt maddeli düzende "4.2")
     */
    public record Price(String description, BigDecimal unitPrice, String unit, String clauseNo) {
    }

    /**
     * @param subClauses   fiyat ve ödeme maddeleri alt maddeli ("4.1.", "5.1.")
     * @param headerFooter her sayfada tekrar eden üst ve alt bilgi (normalizasyon silmeli)
     * @param hyphenate    uzun sözcükler satır sonunda tireyle bölünür (normalizasyon birleştirmeli)
     * @param longClause   ~500 token'ı aşan cezai şart maddesi ve ek maddeler (çok sayfa, paragraf bölmesi)
     * @param imageOnly    taranmış gibi: metin katmanı yok (indeksleme FAILED)
     */
    public record Layout(boolean subClauses, boolean headerFooter, boolean hyphenate, boolean longClause,
            boolean imageOnly) {

        public static Layout plain() {
            return new Layout(false, false, false, false, false);
        }

        public Layout withSubClauses() {
            return new Layout(true, headerFooter, hyphenate, longClause, imageOnly);
        }

        public Layout withHeaderFooter() {
            return new Layout(subClauses, true, hyphenate, longClause, imageOnly);
        }

        public Layout withHyphenation() {
            return new Layout(subClauses, headerFooter, true, longClause, imageOnly);
        }

        public Layout withLongClause() {
            return new Layout(subClauses, headerFooter, hyphenate, true, imageOnly);
        }

        public Layout imageOnlyPage() {
            return new Layout(subClauses, headerFooter, hyphenate, longClause, true);
        }
    }

    public enum Ingest {
        READY,
        FAILED
    }

    /**
     * @param ingest          indeksleme sonucu
     * @param invoiceFile     karşılaştırılacak B-16 faturası; yoksa {@code null}
     * @param result          o faturanın beklenen uyum sonucu; fatura yoksa {@code null}
     * @param finding         beklenen bulgu (insan için); uyumluysa {@code null}
     * @param paymentClauseNo vadenin yazılı olduğu madde ("5" ya da "5.1")
     */
    public record Expectation(Ingest ingest, String invoiceFile, ComplianceCompleted.Result result, String finding,
            String paymentClauseNo) {
    }
}
