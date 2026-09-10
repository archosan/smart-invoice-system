package com.archosan.invoice.compliance.ingest;

import com.archosan.invoice.compliance.ComplianceProperties;
import com.archosan.invoice.compliance.ingest.ContractChunker.Chunk;
import com.archosan.invoice.compliance.testdata.SyntheticContract;
import com.archosan.invoice.compliance.testdata.SyntheticContracts;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Normalizasyon ve madde bazlı chunking (B-45, FR-C1 adım 1–4), B-44'ün sentetik sözleşmeleriyle. */
class ContractChunkerTest {

    private static final Path DIR = Path.of("src/test/resources/contracts");
    private static final ComplianceProperties.Ingest CONFIG = new ComplianceProperties.Ingest(20, 2000, 1600, 200);
    private final ContractChunker chunker = new ContractChunker(CONFIG);

    @Test
    void plainContractHasOneChunkPerClausePlusPreamble() throws IOException {
        List<Chunk> chunks = chunks("contract-01.pdf");

        assertThat(chunks).extracting(Chunk::clauseNo).containsExactly(null, "1", "2", "3", "4", "5", "6", "7");
        assertThat(chunks.get(4).title()).isEqualTo("FİYATLAR");
        assertThat(chunks.get(4).content()).startsWith("MADDE 4 – FİYATLAR\n\n").contains("425,00 TL / koli");
        assertThat(chunks.get(5).content()).contains("30 (otuz) gün");
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.part()).isEqualTo(1);
            assertThat(c.page()).isEqualTo(1);
        });
    }

    /** Alt maddeler ayrı chunk; her biri bağlam için madde başlığıyla başlar, başlık tek başına chunk olmaz. */
    @Test
    void subClausesAreSeparateChunksWithTheirClauseHeading() throws IOException {
        List<Chunk> chunks = chunks("contract-02.pdf");

        assertThat(chunks).extracting(Chunk::clauseNo)
                .containsExactly(null, "1", "2", "3", "4.1", "4.2", "4.3", "4.4", "4.5", "5.1", "5.2", "5.3", "6", "7");
        Chunk rulman = chunks.get(4);
        assertThat(rulman.title()).isEqualTo("FİYATLAR");
        assertThat(rulman.content()).isEqualTo(
                "MADDE 4 – FİYATLAR\n\n4.1. Rulman 6204 ZZ için birim fiyat, adet başına 80,00 TL'dir (KDV hariç).");
        assertThat(chunks.get(9).content()).startsWith("MADDE 5 – ÖDEME KOŞULLARI\n\n5.1. ")
                .contains("30 (otuz) gün");
    }

    /** Üst/alt bilgi silinir, tireler birleşir, uzun madde paragraftan bölünür ve her parça başlıkla başlar. */
    @Test
    void multiPageContractIsNormalizedAndLongClauseIsSplitAtParagraphs() throws IOException {
        SyntheticContract contract = contract("contract-07.pdf");
        List<Chunk> chunks = chunks(contract.file());
        String all = String.join("\n", chunks.stream().map(Chunk::content).toList());

        assertThat(all).doesNotContain("Gizlidir", "Sayfa 1 /", "— " + contract.supplierName())
                .contains("kaynaklandığını", "yürürlükte", "malzemesi")
                .doesNotContainPattern("\\p{L}- \\p{L}|\\p{L}-\\n\\p{L}");
        List<Chunk> penalty = chunks.stream().filter(c -> "7".equals(c.clauseNo())).toList();
        assertThat(penalty).hasSizeGreaterThanOrEqualTo(2);
        assertThat(penalty).extracting(Chunk::part).containsExactly(range(1, penalty.size()));
        assertThat(penalty).allSatisfy(c -> {
            assertThat(c.content()).startsWith("MADDE 7 – CEZAİ ŞART VE GECİKME\n\n");
            assertThat(c.content().length()).isLessThanOrEqualTo(CONFIG.maxClauseChars());
        });
        // Paragraf sınırından: hiçbir parça cümle ortasında bitmez.
        assertThat(penalty).allSatisfy(c -> assertThat(c.content()).endsWith("."));
        assertThat(penalty.getLast().page()).isEqualTo(2);
        assertThat(chunks).extracting(Chunk::clauseNo).contains("8", "9", "10", "11");
    }

    @Test
    void scannedContractHasNoText() throws IOException {
        List<ContractNormalizer.Line> lines = ContractNormalizer.normalize(
                PdfPages.read(DIR.resolve("contract-08.pdf")));

        assertThat(lines).allMatch(ContractNormalizer.Line::isBlank);
    }

    /** Hiç madde kalıbı yoksa pencereli bölme: sınır ve örtüşme sözcük sınırında. */
    @Test
    void textWithoutClausesIsSplitIntoOverlappingWindows() {
        ContractChunker small = new ContractChunker(new ComplianceProperties.Ingest(20, 2000, 100, 20));
        List<ContractNormalizer.Line> lines = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            text.append("sözcük").append(i).append(' ');
        }
        lines.add(new ContractNormalizer.Line(1, text.toString().strip()));

        List<Chunk> chunks = small.chunk(lines);

        assertThat(chunks).hasSizeGreaterThan(3).allSatisfy(c -> {
            assertThat(c.clauseNo()).isNull();
            assertThat(c.content().length()).isLessThanOrEqualTo(100);
            assertThat(c.content()).doesNotStartWith(" ").matches("(sözcük\\d+ ?)+");
        });
        String firstLast = chunks.get(0).content().substring(chunks.get(0).content().lastIndexOf(' ') + 1);
        assertThat(chunks.get(1).content()).as("örtüşme").contains(firstLast);
        assertThat(chunks.getLast().content()).endsWith("sözcük40");
    }

    @Test
    void normalizesToNfc() {
        String decomposed = "İzmir"; // İ: I + birleşik nokta
        List<ContractNormalizer.Line> lines = ContractNormalizer.normalize(List.of(decomposed));

        assertThat(lines.getFirst().text()).isEqualTo("İzmir");
    }

    private List<Chunk> chunks(String file) throws IOException {
        return chunker.chunk(ContractNormalizer.normalize(PdfPages.read(DIR.resolve(file))));
    }

    private static SyntheticContract contract(String file) {
        return SyntheticContracts.all().stream().filter(c -> c.file().equals(file)).findFirst().orElseThrow();
    }

    private static Integer[] range(int from, int to) {
        Integer[] values = new Integer[to - from + 1];
        for (int i = 0; i < values.length; i++) {
            values[i] = from + i;
        }
        return values;
    }
}
