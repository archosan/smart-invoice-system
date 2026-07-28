package com.archosan.invoice.compliance.ingest;

import com.archosan.invoice.compliance.ComplianceProperties;
import com.archosan.invoice.compliance.ingest.ContractNormalizer.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Madde sınırında chunking (FR-C1 adım 3–4, ADR-10).
 *
 * <ul>
 *   <li>{@code ^(MADDE|Madde)\s+\d+} bir madde, {@code ^\d+(\.\d+)*[.)]\s} bir alt madde başlatır; her biri bir
 *       chunk'tır. İlk maddeden önceki metin (başlık, tarih) numarasız bir chunk olur; yalnız başlıktan oluşan madde
 *       (hemen alt maddeye geçen) chunk olmaz.</li>
 *   <li>Alt madde ve uzun maddenin parçaları bağlam için madde başlığıyla başlar ("MADDE 4 – FİYATLAR").</li>
 *   <li>{@code maxClauseChars}'ı aşan madde paragraf sınırından bölünür; tek paragraf sığmıyorsa madde işareti ya da
 *       cümle sınırından, o da yetmezse sözcük sınırından.</li>
 *   <li>Hiç madde kalıbı yoksa metin {@code windowChars} pencerelere, {@code overlapChars} örtüşmeyle bölünür.</li>
 * </ul>
 * Paragraf içindeki satırlar tek boşlukla birleşir, paragraflar boş satırla ayrılır. Chunk'ın sayfası, ilk satırının
 * sayfasıdır.
 */
final class ContractChunker {

    /** {@code clauseNo} ve {@code title} girişte ve pencere bölmesinde {@code null}. */
    record Chunk(String clauseNo, String title, int part, String content, int page) {
    }

    private static final Pattern CLAUSE = Pattern.compile("^(?:MADDE|Madde)\\s+(\\d+)\\s*[–—-]?\\s*(.*)$");
    private static final Pattern SUB_CLAUSE = Pattern.compile("^(\\d+(?:\\.\\d+)*)[.)]\\s");
    /** Uzun paragrafın bölme yerleri: madde işareti önü ve cümle sonu (iki nokta değil: "Kalem: 80,00 TL"). */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\s+(?=• )");

    private record Paragraph(int page, String text) {
    }

    /** Henüz bölünmemiş madde ya da alt madde. */
    private record Unit(String clauseNo, String title, String heading, List<Paragraph> paragraphs) {
    }

    private final int maxChars;
    private final int windowChars;
    private final int overlapChars;

    ContractChunker(ComplianceProperties.Ingest config) {
        this.maxChars = config.maxClauseChars();
        this.windowChars = config.windowChars();
        this.overlapChars = config.overlapChars();
    }

    List<Chunk> chunk(List<Line> lines) {
        List<Unit> units = units(lines);
        if (units.stream().allMatch(u -> u.clauseNo() == null)) {
            return windows(paragraphs(lines));
        }
        List<Chunk> chunks = new ArrayList<>();
        for (Unit unit : units) {
            chunks.addAll(split(unit));
        }
        return chunks;
    }

    private List<Unit> units(List<Line> lines) {
        List<Unit> units = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        String clauseNo = null;
        String title = null;
        String heading = null;
        String clauseTitle = null;
        String clauseHeading = null;
        for (Line line : lines) {
            Matcher clause = CLAUSE.matcher(line.text());
            Matcher subClause = SUB_CLAUSE.matcher(line.text());
            if (clause.matches()) {
                units.add(new Unit(clauseNo, title, heading, paragraphsWithout(current, heading)));
                current = new ArrayList<>();
                clauseNo = clause.group(1);
                clauseTitle = clause.group(2).isBlank() ? null : clause.group(2).strip();
                clauseHeading = line.text();
                title = clauseTitle;
                heading = clauseHeading;
            } else if (subClause.find() && clauseHeading != null) {
                units.add(new Unit(clauseNo, title, heading, paragraphsWithout(current, heading)));
                current = new ArrayList<>();
                clauseNo = subClause.group(1);
                title = clauseTitle;
                heading = clauseHeading;
            }
            current.add(line);
        }
        units.add(new Unit(clauseNo, title, heading, paragraphsWithout(current, heading)));
        return units.stream().filter(u -> !u.paragraphs().isEmpty()).toList();
    }

    /** Birimin paragrafları; madde başlığı satırı ayrıca tutulduğu için gövdeden çıkarılır. */
    private static List<Paragraph> paragraphsWithout(List<Line> lines, String heading) {
        List<Line> body = lines;
        if (heading != null && !lines.isEmpty() && lines.getFirst().text().equals(heading)) {
            body = lines.subList(1, lines.size());
        }
        return paragraphs(body);
    }

    private static List<Paragraph> paragraphs(List<Line> lines) {
        List<Paragraph> paragraphs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int page = -1;
        for (Line line : lines) {
            if (line.isBlank()) {
                if (!text.isEmpty()) {
                    paragraphs.add(new Paragraph(page, text.toString()));
                    text.setLength(0);
                }
                continue;
            }
            if (text.isEmpty()) {
                page = line.page();
            } else {
                text.append(' ');
            }
            text.append(line.text().strip().replaceAll("\\s+", " "));
        }
        if (!text.isEmpty()) {
            paragraphs.add(new Paragraph(page, text.toString()));
        }
        return paragraphs;
    }

    /** Sığarsa tek chunk; sığmazsa paragrafları sınıra kadar doldurur, her parçanın başına madde başlığı. */
    private List<Chunk> split(Unit unit) {
        String prefix = unit.heading() == null ? "" : unit.heading() + "\n\n";
        int budget = maxChars - prefix.length();
        List<Paragraph> pieces = new ArrayList<>();
        for (Paragraph paragraph : unit.paragraphs()) {
            if (paragraph.text().length() <= budget) {
                pieces.add(paragraph);
            } else {
                for (String piece : pack(SENTENCE_END.split(paragraph.text()), budget, " ")) {
                    pieces.add(new Paragraph(paragraph.page(), piece));
                }
            }
        }

        List<Chunk> chunks = new ArrayList<>();
        List<Paragraph> part = new ArrayList<>();
        int length = 0;
        for (Paragraph piece : pieces) {
            int added = (part.isEmpty() ? 0 : 2) + piece.text().length();
            if (!part.isEmpty() && length + added > budget) {
                chunks.add(toChunk(unit, prefix, part, chunks.size() + 1));
                part = new ArrayList<>();
                length = 0;
                added = piece.text().length();
            }
            part.add(piece);
            length += added;
        }
        chunks.add(toChunk(unit, prefix, part, chunks.size() + 1));
        return chunks;
    }

    private static Chunk toChunk(Unit unit, String prefix, List<Paragraph> part, int partNo) {
        String body = String.join("\n\n", part.stream().map(Paragraph::text).toList());
        return new Chunk(unit.clauseNo(), unit.title(), partNo, prefix + body, part.getFirst().page());
    }

    /** Parçaları {@code limit}'e kadar birleştirir; tek parça sığmıyorsa sözcük sınırından böler. */
    private static List<String> pack(String[] parts, int limit, String separator) {
        List<String> packed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            for (String piece : part.length() <= limit ? List.of(part) : byWords(part, limit)) {
                if (!current.isEmpty() && current.length() + separator.length() + piece.length() > limit) {
                    packed.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append(separator);
                }
                current.append(piece);
            }
        }
        if (!current.isEmpty()) {
            packed.add(current.toString());
        }
        return packed;
    }

    private static List<String> byWords(String text, int limit) {
        return pack(text.split(" "), limit, " ").stream()
                .flatMap(s -> s.length() <= limit ? List.of(s).stream() : hardCut(s, limit).stream())
                .toList();
    }

    private static List<String> hardCut(String text, int limit) {
        List<String> cuts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += limit) {
            cuts.add(text.substring(i, Math.min(text.length(), i + limit)));
        }
        return cuts;
    }

    /** Kalıpsız metin: {@code windowChars} pencereler, {@code overlapChars} örtüşme, sözcük sınırında. */
    private List<Chunk> windows(List<Paragraph> paragraphs) {
        StringBuilder text = new StringBuilder();
        List<int[]> pageStarts = new ArrayList<>();   // {offset, page}
        for (Paragraph paragraph : paragraphs) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            pageStarts.add(new int[] {text.length(), paragraph.page()});
            text.append(paragraph.text());
        }
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + windowChars);
            if (end < text.length()) {
                int space = text.lastIndexOf(" ", end);
                end = space > start ? space : end;
            }
            chunks.add(new Chunk(null, null, chunks.size() + 1, text.substring(start, end).strip(),
                    pageAt(pageStarts, start)));
            if (end >= text.length()) {
                break;
            }
            int next = end - overlapChars;
            int space = text.indexOf(" ", Math.max(next, start + 1));
            start = space > start && space < end ? space + 1 : end;
        }
        return chunks;
    }

    private static int pageAt(List<int[]> pageStarts, int offset) {
        int page = pageStarts.isEmpty() ? 1 : pageStarts.getFirst()[1];
        for (int[] start : pageStarts) {
            if (start[0] <= offset) {
                page = start[1];
            }
        }
        return page;
    }
}
