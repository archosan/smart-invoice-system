package com.archosan.invoice.extraction.testdata;

import com.archosan.invoice.extraction.testdata.SyntheticInvoice.CurrencyStyle;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice.ExpectedOutcome;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice.Layout;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 10 sentetik faturanın kataloğu (B-16): 8 temiz + 2 bilerek bozuk (v1 kabul kriteri: en az 8/10 POSTED). Firmalar ve
 * VKN'ler kurgusaldır. Tutarlar kalem başına hesaplanır: net = miktar × birim fiyat, KDV = net × oran / 100,
 * 2 haneye yarım yukarı yuvarlanır.
 */
public final class SyntheticInvoices {

    private SyntheticInvoices() {
    }

    public static List<SyntheticInvoice> all() {
        return List.of(
                invoice("invoice-01.pdf", "Tek kalem, %20 KDV, sade düzen", Layout.plain(),
                        "Anadolu Kırtasiye Ltd. Şti.", "4810293756", "ANK2026000001",
                        date(2026, 9, 1), date(2026, 10, 1),
                        List.of(line("A4 Fotokopi Kağıdı 80 gr (koli)", "10", "425.00", 20)),
                        "Temel durum."),
                invoice("invoice-02.pdf", "5 kalem, karışık KDV oranları (%1, %10, %20)",
                        Layout.plain().withVatBreakdown(),
                        "Ege Endüstriyel Malzeme A.Ş.", "7302918465", "EGE2026000342",
                        date(2026, 9, 3), date(2026, 10, 3),
                        List.of(line("Rulman 6204 ZZ", "24", "86.50", 20),
                                line("Hidrolik yağ 20 L", "3", "1840.00", 20),
                                line("Bakım el kitabı", "2", "350.00", 10),
                                line("Ekmek (personel yemekhanesi)", "40", "12.50", 1),
                                line("İş eldiveni (çift)", "50", "38.90", 10)),
                        "Oran başına KDV satırları; LLM kalemleri ve toplam KDV'yi doğru çıkarmalı."),
                invoice("invoice-03.pdf", "Binlik ayırıcılı büyük tutarlar", Layout.plain(),
                        "Marmara İnşaat Taahhüt A.Ş.", "2950183746", "MRM2026001187",
                        date(2026, 9, 5), date(2026, 11, 4),
                        List.of(line("Çelik konstrüksiyon montajı", "1", "985000.00", 20),
                                line("Beton C30 (m³)", "120", "2450.75", 20)),
                        "1.234.567,89 biçimi; nokta binlik, virgül ondalık (NFR-14)."),
                invoice("invoice-04.pdf", "₺ simgesi ve TL yazısı karışık",
                        Layout.plain().withCurrencyStyle(CurrencyStyle.MIXED_LIRA_SIGN),
                        "Karadeniz Gıda San. ve Tic. Ltd. Şti.", "6619027384", "KRD2026000456",
                        date(2026, 9, 8), date(2026, 9, 22),
                        List.of(line("Fındık içi (kg)", "150", "410.00", 1),
                                line("Ambalaj kolisi", "200", "14.75", 20)),
                        "Birim fiyatlar ₺ ile, toplamlar TL ile; para birimi TRY olmalı."),
                invoice("invoice-05.pdf", "Türkçe karakter yoğun adlar ve açıklamalar", Layout.plain(),
                        "Güneydoğu Çiçekçilik — Şükrü Öztürk", "3847561920", "GND2026000078",
                        date(2026, 9, 10), date(2026, 10, 10),
                        List.of(line("Işıklı süs bitkisi (saksı, büyük boy)", "6", "540.00", 20),
                                line("Gül buketi — ağır kokulu, İğdır üretimi", "12", "275.50", 20),
                                line("Çiçek gübresi (ölçü kaşığıyla)", "4", "189.90", 20)),
                        "ŞĞÜİÖÇ ığüşöç; İ/ı normalizasyonu (NFC)."),
                invoice("invoice-06.pdf", "e-Arşiv benzeri düzen, ayıklanacak gürültü alanlar",
                        Layout.plain().withTitle("e-ARŞİV FATURA").withEArchiveNoise(),
                        "Doğu Yazılım Hizmetleri A.Ş.", "5172039486", "DGY2026004410",
                        date(2026, 9, 12), date(2026, 10, 12),
                        List.of(line("Yazılım bakım hizmeti (Eylül 2026)", "1", "12500.00", 20),
                                line("Bulut sunucu kirası (aylık)", "3", "1750.00", 20)),
                        "ETTN, vergi dairesi, IBAN, MERSİS no; VKN ile karıştırılmamalı."),
                invoice("invoice-07.pdf", "2 sayfa, kalemler ikinci sayfaya taşıyor", Layout.plain(),
                        "İç Anadolu Medikal Ltd. Şti.", "9038174652", "ICA2026000913",
                        date(2026, 9, 15), date(2026, 10, 15),
                        manyLines(),
                        "Toplamlar ikinci sayfanın sonunda."),
                invoice("invoice-08.pdf", "Farklı tarih biçimi (gg/aa/yyyy)",
                        Layout.plain().withDatePattern("dd/MM/yyyy"),
                        "Trakya Tarım Ürünleri Kooperatifi", "1429837560", "TRK2026000230",
                        date(2026, 9, 18), date(2026, 10, 30),
                        List.of(line("Buğday tohumu (kg)", "500", "18.40", 1),
                                line("Gübre (50 kg çuval)", "20", "640.00", 1)),
                        "Tarihler 18/09/2026 biçiminde."),
                inconsistentInvoice(),
                invoice("invoice-10.pdf", "Taranmış gibi: yalnızca resim, metin katmanı yok",
                        Layout.plain().imageOnlyPage(),
                        "Batı Lojistik A.Ş.", "8256019374", "BTL2026000655",
                        date(2026, 9, 22), date(2026, 10, 22),
                        List.of(line("Nakliye bedeli İzmir–İstanbul", "1", "8750.00", 20)),
                        "OCR kapsam dışı; ExtractionFailed(NO_TEXT) beklenir.")
                        .withOutcome(ExpectedOutcome.NO_TEXT));
    }

    /** 9. fatura: kalem toplamı 15.100,00 ama ara toplam 15.400,00 basılı; KDV ve genel toplam basılı ara toplamdan. */
    private static SyntheticInvoice inconsistentInvoice() {
        List<InvoiceLine> lines = List.of(line("Çalışma masası 160x80", "4", "3200.00", 20),
                line("Ergonomik ofis koltuğu", "2", "1150.00", 20));
        BigDecimal printedSubtotal = new BigDecimal("15400.00");
        BigDecimal printedVat = new BigDecimal("3080.00");
        InvoiceFields fields = new InvoiceFields("Akdeniz Ofis Mobilyaları Ltd. Şti.", "6091827345", "AKD2026000321",
                date(2026, 9, 20), date(2026, 10, 20), lines, printedSubtotal, printedVat,
                printedSubtotal.add(printedVat), "TRY");
        return new SyntheticInvoice("invoice-09.pdf", "Bozuk: kalem toplamı ara toplamı tutmuyor",
                ExpectedOutcome.NEEDS_REVIEW, fields, Layout.plain(),
                "Kalemler 15.100,00; basılı ara toplam 15.400,00. LINES_SUM_EQUALS_SUBTOTAL ihlali.");
    }

    private static List<InvoiceLine> manyLines() {
        List<InvoiceLine> lines = new ArrayList<>();
        String[] items = {"Steril gazlı bez", "Enjektör 5 ml", "Eldiven (nitril, M)", "Cerrahi maske",
                "Alkollü mendil", "Serum seti"};
        for (int i = 0; i < 48; i++) {
            String unitPrice = new BigDecimal("12.40").add(new BigDecimal(i).multiply(new BigDecimal("3.15")))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();
            lines.add(line(items[i % items.length] + " — parti " + (i + 1), String.valueOf(5 + (i % 7) * 5),
                    unitPrice, 10));
        }
        return lines;
    }

    private static SyntheticInvoice invoice(String file, String scenario, Layout layout, String supplierName,
            String vkn, String invoiceNo, LocalDate invoiceDate, LocalDate dueDate, List<InvoiceLine> lines,
            String note) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        for (InvoiceLine line : lines) {
            BigDecimal net = net(line);
            subtotal = subtotal.add(net);
            vat = vat.add(vat(line));
        }
        InvoiceFields fields = new InvoiceFields(supplierName, vkn, invoiceNo, invoiceDate, dueDate, lines,
                subtotal.setScale(2, RoundingMode.HALF_UP), vat.setScale(2, RoundingMode.HALF_UP),
                subtotal.add(vat).setScale(2, RoundingMode.HALF_UP), "TRY");
        return new SyntheticInvoice(file, scenario, ExpectedOutcome.POSTED, fields, layout, note);
    }

    static BigDecimal net(InvoiceLine line) {
        return line.quantity().multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal vat(InvoiceLine line) {
        return net(line).multiply(BigDecimal.valueOf(line.vatRate()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static InvoiceLine line(String description, String quantity, String unitPrice, int vatRate) {
        return new InvoiceLine(description, new BigDecimal(quantity), new BigDecimal(unitPrice), vatRate);
    }

    private static LocalDate date(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }
}
