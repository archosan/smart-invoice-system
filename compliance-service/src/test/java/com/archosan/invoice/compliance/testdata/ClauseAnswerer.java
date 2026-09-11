package com.archosan.invoice.compliance.testdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Testlerin "iyi LLM"i (B-46): uyum promptundaki maddelerden soruya yanıtı kurallarla bulur. Fiyat sorusunda tırnak
 * içindeki kalemle başlayan bir fiyat satırı ({@code Kalem: 1.840,00 TL / bidon} ya da {@code 4.1. Kalem için birim
 * fiyat, adet başına 80,00 TL'dir}), vade sorusunda {@code 30 (otuz) gün} aranır; alıntı maddedeki parçanın kendisidir.
 * Bulamazsa {@code found=false}. compliance testleri ve system-tests'in sahte Ollama'sı kullanır.
 */
public final class ClauseAnswerer {

    /** Uyum isteğinin sistem mesajının başı ({@code ClauseValueClient.SYSTEM}). */
    public static final String MARKER = "Sözleşme maddesi okuyucususun.";

    private static final Pattern CLAUSE = Pattern.compile("\\[Madde ([^\\]]+)\\]\\n");
    private static final Pattern ITEM_IN_QUESTION = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern PRICE = Pattern.compile("(\\d{1,3}(?:\\.\\d{3})*,\\d{2}) TL");
    private static final Pattern TERM = Pattern.compile("(\\d+) \\(\\p{L}[\\p{L} ]*\\) gün");
    private static final Pattern UNIT_AFTER_SLASH = Pattern.compile("TL / (\\p{L}+)");
    private static final Pattern UNIT_BEFORE_BASINA = Pattern.compile("(\\p{L}+) başına");

    private record Segment(String clauseNo, String text) {
    }

    private ClauseAnswerer() {
    }

    /** @param prompt sistem + kullanıcı mesajının metni ("Maddeler: … Soru: …") */
    public static String answer(String prompt) {
        int question = prompt.lastIndexOf("Soru: ");
        if (question < 0) {
            return notFound();
        }
        String ask = prompt.substring(question + "Soru: ".length()).strip();
        List<Segment> segments = segments(prompt.substring(0, question));
        Matcher item = ITEM_IN_QUESTION.matcher(ask);
        if (ask.contains("birim fiyat") && item.find()) {
            return price(item.group(1), segments).orElse(notFound());
        }
        if (ask.contains("kaç gün")) {
            return term(segments).orElse(notFound());
        }
        return notFound();
    }

    /** Adı kaleme tam eşit olan satır; yoksa adı kalemin başı olan en uzun satır ("parti 1" ≠ "parti 10"). */
    private static Optional<String> price(String item, List<Segment> segments) {
        Segment best = null;
        String bestName = "";
        for (Segment s : segments) {
            if (!PRICE.matcher(s.text()).find()) {
                continue;
            }
            String name = s.text().replaceFirst("^\\d+(\\.\\d+)*\\.\\s+", "").replaceFirst("^•\\s*", "")
                    .replaceFirst("( için birim fiyat|:\\s+\\d).*$", "").strip();
            if (name.equals(item)) {
                best = s;
                break;
            }
            if (!name.isEmpty() && item.startsWith(name) && name.length() > bestName.length()) {
                best = s;
                bestName = name;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        Segment chosen = best;
        Matcher price = PRICE.matcher(chosen.text());
        price.find();
        String unit = find(UNIT_AFTER_SLASH, chosen.text()).or(() -> find(UNIT_BEFORE_BASINA, chosen.text()))
                .orElse(null);
        return Optional.of(json(price.group(1).replace(".", "").replace(',', '.'), unit, chosen.clauseNo(),
                chosen.text()));
    }

    private static Optional<String> term(List<Segment> segments) {
        for (Segment s : segments) {
            Matcher term = TERM.matcher(s.text());
            if (term.find()) {
                return Optional.of(json(term.group(1), "gün", s.clauseNo(), s.text()));
            }
        }
        return Optional.empty();
    }

    /** Maddeler cümle ve madde işaretinden parçalanır; alıntı maddedeki metnin kendisi kalır. */
    private static List<Segment> segments(String clausesText) {
        List<Segment> segments = new ArrayList<>();
        Matcher m = CLAUSE.matcher(clausesText);
        List<int[]> starts = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[] {m.start(), m.end()});
            numbers.add(m.group(1));
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : clausesText.length();
            String body = clausesText.substring(starts.get(i)[1], end);
            for (String paragraph : body.split("\\n\\n")) {
                for (String part : paragraph.split("(?= • )|(?<=\\D\\.) (?=\\p{Lu})")) {
                    String text = part.strip();
                    if (!text.isEmpty()) {
                        segments.add(new Segment(numbers.get(i), text));
                    }
                }
            }
        }
        return segments;
    }

    private static Optional<String> find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static String notFound() {
        return "{\"found\":false,\"value\":null,\"unit\":null,\"clauseNo\":null,\"quote\":null}";
    }

    private static String json(String value, String unit, String clauseNo, String quote) {
        return "{\"found\":true,\"value\":" + value + ",\"unit\":" + str(unit) + ",\"clauseNo\":"
                + str(clauseNo) + ",\"quote\":" + str(quote) + "}";
    }

    private static String str(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
