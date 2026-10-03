package com.archosan.invoice.extraction.testdata;

import com.archosan.invoice.extraction.testdata.SyntheticInvoice.CurrencyStyle;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Faturanın sayfa düzenini (konumlu metin parçaları) hesaplar; çizim PDF metni veya resim olarak ayrıca yapılır.
 * Koordinatlar PDF noktasıdır (A4 = 595 × 842), y aşağıdan yukarı.
 */
final class InvoiceLayout {

    static final float PAGE_WIDTH = 595;
    static final float PAGE_HEIGHT = 842;

    /** Sayfadaki bir öğe: metin veya grafik çizgi. */
    sealed interface Element permits Text, Rule {
    }

    record Text(float x, float y, float size, String value) implements Element {
    }

    /** Yatay çizgi; gerçek faturalardaki gibi metin değil grafiktir, metin katmanına girmez. */
    record Rule(float x1, float x2, float y) implements Element {
    }

    private static final float MARGIN = 40;
    private static final float ROW = 14;
    private static final float BOTTOM = 90;
    private static final float[] COLUMNS = {40, 62, 330, 385, 465, 500};

    private final SyntheticInvoice invoice;
    private final InvoiceFields fields;
    private final DateTimeFormatter dates;
    private final List<List<Element>> pages = new ArrayList<>();
    private List<Element> page;
    private float y;

    private InvoiceLayout(SyntheticInvoice invoice) {
        this.invoice = invoice;
        this.fields = invoice.fields();
        this.dates = DateTimeFormatter.ofPattern(invoice.layout().datePattern());
    }

    static List<List<Element>> of(SyntheticInvoice invoice) {
        InvoiceLayout layout = new InvoiceLayout(invoice);
        layout.build();
        return layout.pages;
    }

    private void build() {
        newPage();
        text(MARGIN, y, 18, invoice.layout().title());
        y -= 34;

        float top = y;
        text(MARGIN, y, 10, fields.supplierName());
        text(MARGIN, y -= ROW, 9, "Örnek Mah. Sanayi Cad. No: 12 Merkez / Türkiye");
        text(MARGIN, y -= ROW, 9, "VKN: " + fields.supplierVkn());
        if (invoice.layout().eArchiveNoise()) {
            text(MARGIN, y -= ROW, 9, "Vergi Dairesi: Kızılay V.D.");
            text(MARGIN, y -= ROW, 9, "MERSİS No: 0517203948600015");
            text(MARGIN, y -= ROW, 9, "IBAN: TR12 0006 4000 0011 2345 6789 01");
        }
        float left = y;

        y = top;
        float right = 340;
        text(right, y, 9, "Fatura No: " + fields.invoiceNo());
        text(right, y -= ROW, 9, "Fatura Tarihi: " + dates.format(fields.invoiceDate()));
        text(right, y -= ROW, 9, "Vade Tarihi: " + dates.format(fields.dueDate()));
        if (invoice.layout().eArchiveNoise()) {
            text(right, y -= ROW, 9, "ETTN: " + UUID.nameUUIDFromBytes(fields.invoiceNo().getBytes()));
            text(right, y -= ROW, 9, "Senaryo: TEMELFATURA");
        }
        y = Math.min(left, y) - 2 * ROW;

        text(MARGIN, y, 9, "SAYIN");
        text(MARGIN, y -= ROW, 9, "Örnek Alıcı Sanayi A.Ş.");
        text(MARGIN, y -= ROW, 9, "VKN: 9990001112");
        y -= 2 * ROW;

        tableHeader();
        int index = 1;
        for (InvoiceLine line : fields.lines()) {
            if (y < BOTTOM) {
                newPage();
                text(MARGIN, y, 9, fields.invoiceNo() + " numaralı faturanın devamı");
                y -= 2 * ROW;
                tableHeader();
            }
            row(index++, line);
        }

        if (y < BOTTOM + 5 * ROW) {
            newPage();
        }
        y -= ROW;
        float labels = 385;
        float values = 480;
        text(labels, y, 9, "Ara Toplam:");
        text(values, y, 9, total(fields.subtotal()));
        if (invoice.layout().vatBreakdown()) {
            for (Map.Entry<Integer, BigDecimal> rate : vatByRate().entrySet()) {
                text(labels, y -= ROW, 9, "KDV (%" + rate.getKey() + "):");
                text(values, y, 9, total(rate.getValue()));
            }
        }
        text(labels, y -= ROW, 9, "KDV Toplamı:");
        text(values, y, 9, total(fields.vatTotal()));
        text(labels, y -= ROW, 10, "Genel Toplam:");
        text(values, y, 10, total(fields.grandTotal()));

        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).add(new Text(MARGIN, 40, 8, "Sayfa " + (i + 1) + " / " + pages.size()));
        }
    }

    private void tableHeader() {
        String[] headers = {"Sıra", "Açıklama", "Miktar", "Birim Fiyat", "KDV %", "Tutar"};
        for (int i = 0; i < headers.length; i++) {
            text(COLUMNS[i], y, 9, headers[i]);
        }
        y -= 4;
        page.add(new Rule(MARGIN, PAGE_WIDTH - MARGIN, y));
        y -= ROW;
    }

    private void row(int index, InvoiceLine line) {
        text(COLUMNS[0], y, 9, String.valueOf(index));
        text(COLUMNS[1], y, 9, line.description());
        text(COLUMNS[2], y, 9, quantity(line.quantity()));
        text(COLUMNS[3], y, 9, unitPrice(line.unitPrice()));
        text(COLUMNS[4], y, 9, "%" + line.vatRate());
        text(COLUMNS[5], y, 9, total(SyntheticInvoices.net(line)));
        y -= ROW;
    }

    private Map<Integer, BigDecimal> vatByRate() {
        Map<Integer, BigDecimal> byRate = new TreeMap<>();
        for (InvoiceLine line : fields.lines()) {
            byRate.merge(line.vatRate(), SyntheticInvoices.vat(line), BigDecimal::add);
        }
        return byRate;
    }

    private void newPage() {
        page = new ArrayList<>();
        pages.add(page);
        y = PAGE_HEIGHT - 50;
    }

    private void text(float x, float atY, float size, String value) {
        page.add(new Text(x, atY, size, value));
    }

    private String unitPrice(BigDecimal amount) {
        return invoice.layout().currencyStyle() == CurrencyStyle.MIXED_LIRA_SIGN
                ? "₺" + money(amount)
                : money(amount) + " TL";
    }

    private static String total(BigDecimal amount) {
        return money(amount) + " TL";
    }

    /** Türkçe biçim: {@code 1.234.567,89}. */
    static String money(BigDecimal amount) {
        return new DecimalFormat("#,##0.00", turkishSymbols()).format(amount);
    }

    static String quantity(BigDecimal quantity) {
        return new DecimalFormat("#,##0.###", turkishSymbols()).format(quantity);
    }

    private static DecimalFormatSymbols turkishSymbols() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        return symbols;
    }
}
