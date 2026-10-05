package com.archosan.invoice.systemtests;

import com.archosan.invoice.compliance.testdata.SyntheticContract;
import com.archosan.invoice.compliance.testdata.SyntheticContracts;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sözleşme uyumu uçtan uca (B-46, US-06, US-07): yükleme → çıkarım → uyum kontrolü (gerçek seçim, embedding, sahte
 * LLM'in maddeden okuduğu değer, grounding) → onaycı. Her test kendi VKN'siyle çalışır.
 */
class ContractComplianceSystemTest extends SystemTest {

    /** US-06: fiyatı sözleşmenin üstünde fatura onaya düşer; onaycı madde, alıntı ve farkı görür, portala gidilmez. */
    @Test
    void priceAboveContractWaitsForApprovalWithClauseAndQuote() {
        String vkn = Invoices.randomVkn();
        stack.contracts().uploadAndAwaitReady(SyntheticContracts.forSupplier(contract("contract-02.pdf"), vkn,
                "fiyat-" + vkn + ".pdf"));
        SyntheticInvoice invoice = Invoices.forSupplier(Invoices.byFile("invoice-02.pdf"), vkn, llm);

        UUID id = uploadRendered(invoice);

        assertThat(api.awaitSettled(id, SETTLE)).isEqualTo("PENDING_APPROVAL");
        JsonNode compliance = api.get(id).path("compliance");
        assertThat(compliance.path("result").asString()).isEqualTo("NON_COMPLIANT");
        assertThat(compliance.path("findings")).singleElement().satisfies(f -> {
            assertThat(f.path("check").asString()).isEqualTo("UNIT_PRICE");
            assertThat(f.path("clauseNo").asString()).isEqualTo("4.1");
            assertThat(f.path("quote").asString()).contains("80,00 TL");
            assertThat(f.path("invoiceValue").asString()).isEqualTo("Rulman 6204 ZZ: 86.50");
            assertThat(f.path("contractValue").asString()).isEqualTo("80.00 / adet");
            assertThat(f.path("reliable").asBoolean()).isTrue();
        });
        assertThat(outboxRows(id, "PostToPortal")).isZero();
        assertThat(submission(id)).isNull();
    }

    /** US-07: sözleşmesi olmayan tedarikçinin faturası onaya düşer. */
    @Test
    void supplierWithoutContractWaitsForApproval() {
        SyntheticInvoice invoice = Invoices.forSupplier(Invoices.byFile("invoice-01.pdf"), Invoices.randomVkn(), llm);

        UUID id = uploadRendered(invoice);

        assertThat(api.awaitSettled(id, SETTLE)).isEqualTo("PENDING_APPROVAL");
        assertThat(api.get(id).path("compliance").path("result").asString()).isEqualTo("NO_CONTRACT");
        assertThat(submission(id)).isNull();
    }

    private static SyntheticContract contract(String file) {
        return SyntheticContracts.all().stream().filter(c -> c.file().equals(file)).findFirst().orElseThrow();
    }
}
