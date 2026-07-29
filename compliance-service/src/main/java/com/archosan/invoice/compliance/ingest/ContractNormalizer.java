package com.archosan.invoice.compliance.ingest;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Normalizasyon (FR-C1 adım 2): Unicode NFC (İ/ı ve birleşik işaretler tek biçimde), birden fazla sayfada aynı olan
 * üst ve alt bilgi satırlarının silinmesi (rakamlar yok sayılarak: "Sayfa 1 / 3" ile "Sayfa 2 / 3" aynıdır), satır
 * sonunda tireyle bölünmüş sözcüklerin birleştirilmesi. Sonuç sayfa numaralı satırlardır; boş satır paragraf
 * sınırıdır.
 */
final class ContractNormalizer {

    /** Sayfa numaralı satır; {@code text} boşsa paragraf sınırı. */
    record Line(int page, String text) {

        boolean isBlank() {
            return text.isBlank();
        }
    }

    private static final Pattern LINE_END_HYPHEN = Pattern.compile("(\\p{L})-\\n(\\p{L})");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private ContractNormalizer() {
    }

    static List<Line> normalize(List<String> pageTexts) {
        List<List<String>> pages = pageTexts.stream()
                .map(text -> Normalizer.normalize(text, Normalizer.Form.NFC))
                .map(text -> List.of(text.split("\\n", -1)))
                .toList();
        pages = removeRepeated(pages, ContractNormalizer::firstContentIndex);
        pages = removeRepeated(pages, ContractNormalizer::lastContentIndex);

        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            String joined = LINE_END_HYPHEN.matcher(String.join("\n", pages.get(i))).replaceAll("$1$2");
            for (String line : joined.split("\\n", -1)) {
                lines.add(new Line(i + 1, line.strip()));
            }
            lines.add(new Line(i + 1, ""));   // sayfa sonu paragrafı da kapatır
        }
        return lines;
    }

    /**
     * Her sayfanın {@code position}'daki (ilk ya da son dolu) satırı, rakamlar yok sayılınca en az iki sayfada ve
     * sayfaların en az yarısında aynıysa silinir. Tek sayfalık belgede bir şey silinmez.
     */
    private static List<List<String>> removeRepeated(List<List<String>> pages,
            Function<List<String>, Integer> position) {
        if (pages.size() < 2) {
            return pages;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (List<String> page : pages) {
            Integer index = position.apply(page);
            if (index != null) {
                counts.merge(key(page.get(index)), 1, Integer::sum);
            }
        }
        int threshold = Math.max(2, (pages.size() + 1) / 2);
        List<List<String>> result = new ArrayList<>();
        for (List<String> page : pages) {
            Integer index = position.apply(page);
            if (index != null && counts.get(key(page.get(index))) >= threshold) {
                List<String> trimmed = new ArrayList<>(page);
                trimmed.set(index, "");
                result.add(trimmed);
            } else {
                result.add(page);
            }
        }
        return result;
    }

    private static String key(String line) {
        return DIGITS.matcher(line.strip()).replaceAll("#");
    }

    private static Integer firstContentIndex(List<String> page) {
        for (int i = 0; i < page.size(); i++) {
            if (!page.get(i).isBlank()) {
                return i;
            }
        }
        return null;
    }

    private static Integer lastContentIndex(List<String> page) {
        for (int i = page.size() - 1; i >= 0; i--) {
            if (!page.get(i).isBlank()) {
                return i;
            }
        }
        return null;
    }
}
