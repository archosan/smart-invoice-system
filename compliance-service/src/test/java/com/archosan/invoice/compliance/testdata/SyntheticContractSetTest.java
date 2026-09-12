package com.archosan.invoice.compliance.testdata;

import com.archosan.invoice.compliance.testdata.SyntheticContract.Ingest;
import com.archosan.invoice.compliance.testdata.SyntheticContract.Price;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.messaging.message.ComplianceCompleted.Result;
import com.archosan.invoice.messaging.message.InvoiceFields;
import com.archosan.invoice.messaging.message.InvoiceLine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commit edilmiş sözleşme setinin bütünlüğü (B-44): katalogla aynı mı, beklenen uyum sonuçları faturalarla tutarlı
 * mı, PDF'ler B-45'in sınayacağı özellikleri (madde kalıbı, üst/alt bilgi, tireler, uzun madde, metinsizlik) taşıyor
 * mu.
 */
class SyntheticContractSetTest {

    private static final Path DIR = Path.of("src/test/resources/contracts");
    private static final JsonMapper JSON = SyntheticContractGenerator.expectedJsonMapper();
    private static final Pattern CLAUSE = Pattern.compile("^(MADDE|Madde)\\s+\\d+", Pattern.MULTILINE);
    private static final Pattern SUB_CLAUSE = Pattern.compile("^\\d+(\\.\\d+)*[.)]\\s", Pattern.MULTILINE);

    static List<SyntheticContract> contracts() {
        return SyntheticContracts.all();
    }

    @Test
    void coversEveryComplianceResultAndIngestFailure() {
        assertThat(contracts()).hasSize(8).extracting(SyntheticContract::file).doesNotHaveDuplicates();
        assertThat(contracts()).extracting(c -> c.expectation().result())
                .contains(Result.COMPLIANT, Result.NON_COMPLIANT, Result.NO_CONTRACT, Result.CONTRACT_CONFLICT);
        assertThat(contracts()).filteredOn(c -> c.expectation().ingest() == Ingest.FAILED).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void committedExpectedJsonMatchesCatalog(SyntheticContract contract) throws IOException {
        Path expected = DIR.resolve(SyntheticContractGenerator.expectedFileName(contract.file()));

        assertThat(DIR.resolve(contract.file())).exists();
        assertThat(JSON.readValue(Files.readString(expected), SyntheticContract.class))
                .as("Katalog değişti ama üretici yeniden çalıştırılmadı mı?")
                .isEqualTo(contract);
        assertThat(JSON.readTree(Files.readString(expected)).at("/prices/0/unitPrice").isString()).isTrue();
    }

    /** Betiklerin yüklediği uyumlu sözleşmeler (B-46) de katalogla aynı; her temiz faturanın bir sözleşmesi var. */
    @Test
    void committedCompliantContractsMatchCatalog() throws IOException {
        Path dir = DIR.resolve(SyntheticContractGenerator.COMPLIANT_DIR);
        assertThat(SyntheticContracts.compliantSet()).hasSize(8).allSatisfy(contract -> {
            assertThat(dir.resolve(contract.file())).exists();
            assertThat(JSON.readValue(Files.readString(dir.resolve(
                    SyntheticContractGenerator.expectedFileName(contract.file()))), SyntheticContract.class))
                    .as(contract.file()).isEqualTo(contract);
        });
    }

    /** Sözleşme faturanın tedarikçisine yazılı; her fatura kalemi bir sözleşme fiyatıyla eşleşir. */
    @ParameterizedTest
    @MethodSource("contracts")
    void belongsToItsInvoiceSupplierAndCoversEveryLine(SyntheticContract contract) {
        InvoiceFields invoice = SyntheticContracts.invoice(contract.expectation().invoiceFile()).fields();

        assertThat(contract.supplierVkn()).isEqualTo(invoice.supplierVkn());
        assertThat(contract.supplierName()).isEqualTo(invoice.supplierName());
        assertThat(contract.validFrom()).isBefore(contract.validTo());
        assertThat(invoice.lines()).allSatisfy(line -> assertThat(priceFor(contract, line)).as(line.description())
                .isPresent());
    }

    /** Beklenen sonuç, §4.3'teki kurallar faturaya elle uygulandığında çıkan sonuçtur. */
    @ParameterizedTest
    @MethodSource("contracts")
    void expectedResultFollowsFromInvoiceAndContracts(SyntheticContract contract) {
        if (contract.expectation().ingest() == Ingest.FAILED) {
            assertThat(contract.expectation().result()).isNull();
            return;
        }
        InvoiceFields invoice = SyntheticContracts.invoice(contract.expectation().invoiceFile()).fields();
        List<SyntheticContract> valid = contracts().stream()
                .filter(c -> c.expectation().ingest() == Ingest.READY)
                .filter(c -> c.supplierVkn().equals(invoice.supplierVkn()))
                .filter(c -> !invoice.invoiceDate().isBefore(c.validFrom()) && !invoice.invoiceDate().isAfter(c.validTo()))
                .toList();

        Result expected;
        if (valid.isEmpty()) {
            expected = Result.NO_CONTRACT;
        } else if (valid.size() > 1) {
            expected = Result.CONTRACT_CONFLICT;
        } else {
            SyntheticContract only = valid.getFirst();
            long term = ChronoUnit.DAYS.between(invoice.invoiceDate(), invoice.dueDate());
            boolean priceExceeded = invoice.lines().stream()
                    .anyMatch(line -> line.unitPrice().compareTo(priceFor(only, line).orElseThrow().unitPrice()) > 0);
            expected = priceExceeded || term != only.paymentTermDays() ? Result.NON_COMPLIANT : Result.COMPLIANT;
        }
        assertThat(contract.expectation().result()).isEqualTo(expected);
        assertThat(contract.expectation().finding() == null).isEqualTo(expected == Result.COMPLIANT);
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void textLayerCarriesClausesPricesAndTermExceptForTheScannedContract(SyntheticContract contract)
            throws IOException {
        String text = text(contract, 1, Integer.MAX_VALUE);

        if (contract.expectation().ingest() == Ingest.FAILED) {
            assertThat(text).isBlank();
            return;
        }
        assertThat(CLAUSE.matcher(text).results().count()).isGreaterThanOrEqualTo(7);
        assertThat(text).contains(contract.supplierVkn(), ContractText.date(contract.validFrom()),
                ContractText.date(contract.validTo()), contract.paymentTermDays() + " (");
        for (Price price : contract.prices()) {
            assertThat(text).contains(ContractText.money(price.unitPrice()) + " TL");
        }
        if (contract.layout().subClauses()) {
            assertThat(SUB_CLAUSE.matcher(text).results().map(m -> m.group().strip()).toList())
                    .containsAll(contract.prices().stream().map(p -> p.clauseNo() + ".").toList())
                    .contains(contract.expectation().paymentClauseNo() + ".");
        } else {
            assertThat(SUB_CLAUSE.matcher(text).find()).as("alt madde yok").isFalse();
        }
    }

    /** contract-07: her sayfada aynı üst/alt bilgi, satır sonu tireleri ve tek başına uzun cezai şart maddesi. */
    @Test
    void longContractHasRepeatedChromeHyphensAndALongClause() throws IOException {
        SyntheticContract contract = byFile().get("contract-07.pdf");
        int pages;
        try (PDDocument document = Loader.loadPDF(DIR.resolve(contract.file()).toFile())) {
            pages = document.getNumberOfPages();
        }

        assertThat(pages).isGreaterThanOrEqualTo(2);
        for (int page = 1; page <= pages; page++) {
            assertThat(text(contract, page, page)).as("sayfa " + page)
                    .contains(ContractText.TITLE + " — " + contract.supplierName())
                    .contains("Sayfa " + page + " / " + pages);
        }
        assertThat(Pattern.compile("\\p{L}-\\R\\p{L}").matcher(text(contract, 1, pages)).find())
                .as("satır sonunda tireyle bölünmüş sözcük").isTrue();
        // ~500 token: Türkçe metinde kabaca 4 karakter/token.
        assertThat(String.join("\n", ContractText.longPenaltyClause()).length()).isGreaterThan(2000);
    }

    @Test
    void conflictingContractsOverlapOnTheInvoiceDate() {
        SyntheticContract first = byFile().get("contract-04.pdf");
        SyntheticContract second = byFile().get("contract-05.pdf");
        SyntheticInvoice invoice = SyntheticContracts.invoice("invoice-06.pdf");

        assertThat(first.supplierVkn()).isEqualTo(second.supplierVkn());
        assertThat(second.validFrom()).isBefore(first.validTo());
        assertThat(invoice.fields().invoiceDate()).isAfterOrEqualTo(second.validFrom())
                .isBeforeOrEqualTo(first.validTo());
    }

    private static Optional<Price> priceFor(SyntheticContract contract, InvoiceLine line) {
        return contract.prices().stream().filter(p -> line.description().startsWith(p.description())).findFirst();
    }

    private static String text(SyntheticContract contract, int startPage, int endPage) throws IOException {
        try (PDDocument document = Loader.loadPDF(DIR.resolve(contract.file()).toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(endPage);
            return stripper.getText(document);
        }
    }

    private static Map<String, SyntheticContract> byFile() {
        return contracts().stream().collect(Collectors.toMap(SyntheticContract::file, Function.identity()));
    }
}
