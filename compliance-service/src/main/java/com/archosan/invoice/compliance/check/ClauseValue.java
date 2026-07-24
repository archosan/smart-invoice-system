package com.archosan.invoice.compliance.check;

import java.math.BigDecimal;

/**
 * LLM'in maddelerden çıkardığı tek değer (FR-C2 adım 4): {@code {bulunduMu, deger, birim, maddeNo, alinti}}.
 * {@code value} JSON sayısıdır (fiyat {@code 80.00}, vade gün {@code 30}): şemada metin olduğunda gerçek model değerin
 * önüne çöp ekliyordu ("strconv(425.00)", "os30"; B-46 ölçümü).
 */
record ClauseValue(boolean found, BigDecimal value, String unit, String clauseNo, String quote) {
}
