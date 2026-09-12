package com.archosan.invoice.compliance.testdata;

import com.archosan.invoice.compliance.testdata.SyntheticContract.Expectation;
import com.archosan.invoice.compliance.testdata.SyntheticContract.Ingest;
import com.archosan.invoice.compliance.testdata.SyntheticContract.Layout;
import com.archosan.invoice.compliance.testdata.SyntheticContract.Price;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Result;
import com.archosan.invoice.messaging.message.InvoiceFields;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 8 sentetik sözleşmenin kataloğu (B-44). Tedarikçiler B-16 faturalarınınkilerdir (VKN, unvan ve kalem açıklamaları
 * fatura kataloğundan okunur); her senaryo bir faturanın uyum sonucunu belirler. Faturası sözleşmesiz kalanlar
 * (03, 05, 09, 10) B-46'da {@code NO_CONTRACT} alır. Fatura tarihleri 2026 Eylül'dedir.
 */
public final class SyntheticContracts {

    private SyntheticContracts() {
    }

    public static List<SyntheticContract> all() {
        return List.of(
                contract("contract-01.pdf", "Fiyat ve vade faturayla aynı, sade düzen", "invoice-01.pdf",
                        date(2026, 1, 1), date(2026, 12, 31), 30,
                        List.of(price("A4 Fotokopi Kağıdı 80 gr", "425.00", "koli", "4")),
                        Layout.plain(), Result.COMPLIANT, null, "5",
                        "Temel durum: fatura birim fiyatı = sözleşme fiyatı, vade 30 gün."),
                contract("contract-02.pdf", "Bir kalemde fiyat aşımı, alt maddeli düzen", "invoice-02.pdf",
                        date(2026, 1, 1), date(2026, 12, 31), 30,
                        List.of(price("Rulman 6204 ZZ", "80.00", "adet", "4.1"),
                                price("Hidrolik yağ 20 L", "1840.00", "bidon", "4.2"),
                                price("Bakım el kitabı", "350.00", "adet", "4.3"),
                                price("Ekmek", "12.50", "adet", "4.4"),
                                price("İş eldiveni", "38.90", "çift", "4.5")),
                        Layout.plain().withSubClauses(), Result.NON_COMPLIANT,
                        "Rulman 6204 ZZ: fatura 86,50 TL > sözleşme 80,00 TL (Madde 4.1)", "5.1",
                        "Alt madde kalıbı ^\\d+(\\.\\d+)*[.)]\\s; her kalem ayrı alt madde, sorgu kalem başına."),
                contract("contract-03.pdf", "Vade farkı: sözleşme 30 gün, fatura 14 gün", "invoice-04.pdf",
                        date(2026, 3, 1), date(2027, 2, 28), 30,
                        List.of(price("Fındık içi", "410.00", "kg", "4"),
                                price("Ambalaj kolisi", "14.75", "adet", "4")),
                        Layout.plain(), Result.NON_COMPLIANT,
                        "Vade: fatura 14 gün ≠ sözleşme 30 gün (Madde 5)", "5",
                        "Fiyatlar tutar; yalnız vade farklı. Vade = fatura vadesi − fatura tarihi."),
                contract("contract-04.pdf", "Çakışan aralık (1/2): takvim yılı", "invoice-06.pdf",
                        date(2026, 1, 1), date(2026, 12, 31), 30,
                        doguPrices(), Layout.plain(), Result.CONTRACT_CONFLICT,
                        "Fatura tarihinde iki geçerli sözleşme (contract-04, contract-05)", "5",
                        "Seçim SQL'de: 2+ sonuç → CONTRACT_CONFLICT; LLM'e gidilmez."),
                contract("contract-05.pdf", "Çakışan aralık (2/2): Temmuz'dan başlayan yenileme", "invoice-06.pdf",
                        date(2026, 7, 1), date(2027, 6, 30), 30,
                        doguPrices(), Layout.plain(), Result.CONTRACT_CONFLICT,
                        "Fatura tarihinde iki geçerli sözleşme (contract-04, contract-05)", "5",
                        "Yükleme API'si çakışmayı kabul eder ama uyarı döner (§4.3)."),
                contract("contract-06.pdf", "Süresi dolmuş sözleşme", "invoice-08.pdf",
                        date(2025, 1, 1), date(2025, 12, 31), 42,
                        List.of(price("Buğday tohumu", "18.40", "kg", "4"),
                                price("Gübre", "640.00", "çuval", "4")),
                        Layout.plain(), Result.NO_CONTRACT,
                        "Fatura tarihinde (18.09.2026) geçerli sözleşme yok", "5",
                        "Değerler tutsa da sözleşme seçilmez; süre kontrolü SQL'de biter."),
                contract("contract-07.pdf", "Çok sayfa: üst/alt bilgi, satır sonu tireleri, uzun madde",
                        "invoice-07.pdf",
                        date(2026, 6, 1), date(2027, 5, 31), 30,
                        List.of(price("Steril gazlı bez", "175.00", "paket", "4"),
                                price("Enjektör 5 ml", "175.00", "kutu", "4"),
                                price("Eldiven (nitril, M)", "175.00", "kutu", "4"),
                                price("Cerrahi maske", "175.00", "kutu", "4"),
                                price("Alkollü mendil", "175.00", "paket", "4"),
                                price("Serum seti", "175.00", "adet", "4")),
                        Layout.plain().withHeaderFooter().withHyphenation().withLongClause(), Result.COMPLIANT,
                        null, "5",
                        "Normalizasyon (tekrar eden üst/alt bilgi, tireli satır sonu) ve ~500 token'ı aşan maddenin "
                                + "paragraftan bölünmesi; faturadaki 48 kalemin fiyatı 175,00 TL tavanın altında."),
                contract("contract-08.pdf", "Taranmış gibi: yalnızca resim, metin katmanı yok", "invoice-10.pdf",
                        date(2026, 1, 1), date(2026, 12, 31), 30,
                        List.of(price("Nakliye bedeli İzmir–İstanbul", "8750.00", "sefer", "4")),
                        Layout.plain().imageOnlyPage(), null, null, "5",
                        "OCR kapsam dışı; indeksleme FAILED. Faturası da metinsiz, uyum kontrolüne hiç gelmez.")
                        .failing());
    }

    /**
     * Faturayla birebir uyumlu sözleşme (B-46; system-tests her tedarikçiye yükler): her farklı kalem faturadaki en
     * yüksek birim fiyatıyla, vade faturanın vadesiyle, 2026 takvim yılı, sade düzen. Böylece uyum kontrolü gerçek yolu
     * izler ve fatura {@code COMPLIANT} olur.
     */
    public static SyntheticContract compliantWith(SyntheticInvoice invoice, String file) {
        InvoiceFields f = invoice.fields();
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        f.lines().forEach(line -> prices.merge(line.description(), line.unitPrice(), BigDecimal::max));
        int term = (int) ChronoUnit.DAYS.between(f.invoiceDate(), f.dueDate());
        return new SyntheticContract(file, "Faturayla uyumlu: " + invoice.file(), f.supplierName(),
                f.supplierVkn(), date(2026, 1, 1), date(2026, 12, 31), term,
                prices.entrySet().stream().map(e -> new Price(e.getKey(), e.getValue(), "adet", "4")).toList(),
                Layout.plain(), new Expectation(Ingest.READY, invoice.file(), Result.COMPLIANT, null, "5"),
                "B-46 sistem testleri");
    }

    /**
     * Her temiz B-16 faturası için uyumlu sözleşme; dosya adı {@code uyumlu-invoice-XX.pdf}. Commit edilir
     * ({@code contracts/compliant}); gerçek yığında çalışan betikler faturadan önce yükler, sistem testleri aynısını
     * üretir.
     */
    public static List<SyntheticContract> compliantSet() {
        return SyntheticInvoices.all().stream()
                .filter(i -> i.expectedOutcome() == SyntheticInvoice.ExpectedOutcome.POSTED)
                .map(i -> compliantWith(i, "uyumlu-" + i.file()))
                .toList();
    }

    /** Aynı sözleşme, başka bir tedarikçi için (sistem testlerinde testler birbirinin sözleşmesini görmesin). */
    public static SyntheticContract forSupplier(SyntheticContract contract, String vkn, String file) {
        return new SyntheticContract(file, contract.scenario(), contract.supplierName(), vkn, contract.validFrom(),
                contract.validTo(), contract.paymentTermDays(), contract.prices(), contract.layout(),
                contract.expectation(), contract.note());
    }

    private static List<Price> doguPrices() {
        return List.of(price("Yazılım bakım hizmeti", "12500.00", "ay", "4"),
                price("Bulut sunucu kirası", "1750.00", "ay", "4"));
    }

    private static SyntheticContract contract(String file, String scenario, String invoiceFile, LocalDate validFrom,
            LocalDate validTo, int paymentTermDays, List<Price> prices, Layout layout, Result result, String finding,
            String paymentClauseNo, String note) {
        SyntheticInvoice invoice = invoice(invoiceFile);
        return new SyntheticContract(file, scenario, invoice.fields().supplierName(), invoice.fields().supplierVkn(),
                validFrom, validTo, paymentTermDays, prices, layout,
                new Expectation(Ingest.READY, invoiceFile, result, finding, paymentClauseNo), note);
    }

    static SyntheticInvoice invoice(String file) {
        return SyntheticInvoices.all().stream().filter(i -> i.file().equals(file)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("B-16'da yok: " + file));
    }

    private static Price price(String description, String unitPrice, String unit, String clauseNo) {
        return new Price(description, new BigDecimal(unitPrice), unit, clauseNo);
    }

    private static LocalDate date(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }
}
