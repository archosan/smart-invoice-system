package com.archosan.invoice.extraction.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link LlmInvoice}'ın JSON şeması; Ollama'ya {@code format} olarak verilir ve model şema dışına çıkamaz (B-18 K2).
 * Elle yazılır ki şema prompt sürümüyle birlikte bilinçli değişsin.
 *
 * <p>Her alanın {@code maxLength}'i vardır (B-29): Ollama şemayı dilbilgisine çevirir, model bir alanın içinde tekrar
 * döngüsüne giremez (prompt v2 ile {@code "vatRate": "20 %20 %20 …"} görüldü). Sınırlar gerçek değerlerin rahatça
 * sığacağı kadar geniştir; aşan değer kesilir ve kurallar (ayrıştırma, toplamlar) onu yakalar.
 */
public final class InvoiceSchema {

    private static final Map<String, Integer> HEADER = ordered(
            "supplierName", 200, "supplierVkn", 20, "invoiceNo", 50, "invoiceDate", 20, "dueDate", 20,
            "subtotal", 30, "vatTotal", 30, "grandTotal", 30, "currency", 10);
    private static final Map<String, Integer> LINE = ordered(
            "description", 300, "quantity", 20, "unitPrice", 30, "vatRate", 10);
    private static final int MAX_LINES = 300;

    private InvoiceSchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        HEADER.forEach((name, max) -> properties.put(name, string(max)));
        properties.put("lines", Map.of("type", "array", "maxItems", MAX_LINES, "items", object(LINE)));
        List<String> required = new ArrayList<>(HEADER.keySet());
        required.add("lines");
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private static Map<String, Object> object(Map<String, Integer> stringFields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        stringFields.forEach((name, max) -> properties.put(name, string(max)));
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(stringFields.keySet()));
    }

    private static Map<String, Object> string(int maxLength) {
        return Map.of("type", "string", "maxLength", maxLength);
    }

    private static Map<String, Integer> ordered(Object... nameAndMax) {
        Map<String, Integer> fields = new LinkedHashMap<>();
        for (int i = 0; i < nameAndMax.length; i += 2) {
            fields.put((String) nameAndMax[i], (Integer) nameAndMax[i + 1]);
        }
        return fields;
    }
}
