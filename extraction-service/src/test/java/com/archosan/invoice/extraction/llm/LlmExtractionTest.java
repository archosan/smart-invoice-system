package com.archosan.invoice.extraction.llm;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.llm.LlmSemaphore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmExtractionTest {

    private static final LlmInvoice GOOD_INVOICE = new LlmInvoice("ACME", "1234567890", "F-1", "01.09.2026",
            "01.10.2026", List.of(), "10,00 TL", "2,00 TL", "12,00 TL", "TL");
    private static final LlmAnswer GOOD = new LlmAnswer(GOOD_INVOICE, "{\"supplierName\":\"ACME\"}");

    private final LlmInvoiceClient client = mock(LlmInvoiceClient.class);
    private final LlmSemaphore semaphore = mock(LlmSemaphore.class);
    private final LlmExtraction extraction = new LlmExtraction(client, semaphore, ExtractionProperties.of(
            Path.of("/x"), 20, new ExtractionProperties.Llm("m", Duration.ofSeconds(1), 2, 1, Duration.ofSeconds(1),
            3, Duration.ofSeconds(30), 8192, 4096, "v2")));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void semaphoreRunsWork() {
        when(semaphore.withPermit(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
    }

    @Test
    void firstGoodAnswerSucceedsInOneAttempt() {
        when(client.extract(eq("metin"), isNull())).thenReturn(GOOD);

        LlmExtraction.Result result = extraction.extract("metin");

        assertThat(result).isInstanceOfSatisfying(LlmExtraction.Succeeded.class,
                ok -> assertThat(ok.invoice()).isEqualTo(GOOD_INVOICE));
        assertThat(result.history()).singleElement().satisfies(attempt -> {
            assertThat(attempt.attemptNo()).isEqualTo(1);
            assertThat(attempt.rawOutput()).isEqualTo("{\"supplierName\":\"ACME\"}");
            assertThat(attempt.error()).isNull();
        });
    }

    @Test
    void badOutputIsFedBackIntoNextAttempt() {
        LlmOutputException bad = new LlmOutputException("LLM yanıtı çözülemedi: x", "{bozuk", null);
        when(client.extract(eq("metin"), isNull())).thenThrow(bad);
        when(client.extract(eq("metin"), same(bad))).thenReturn(GOOD);

        LlmExtraction.Result result = extraction.extract("metin");

        assertThat(result).isInstanceOf(LlmExtraction.Succeeded.class);
        assertThat(result.history()).extracting(LlmAttempt::rawOutput).containsExactly("{bozuk", GOOD.rawOutput());
        assertThat(result.history().getFirst().error()).isEqualTo("LLM yanıtı çözülemedi: x");
    }

    @Test
    void contextOverflowEndsWithoutFurtherAttempts() {
        when(client.extract(eq("metin"), any()))
                .thenThrow(new LlmOutputLimitException("LLM bağlamı taştı: …", "{uydurma"));

        LlmExtraction.Result result = extraction.extract("metin");

        assertThat(result).isInstanceOfSatisfying(LlmExtraction.Exhausted.class,
                exhausted -> assertThat(exhausted.lastError()).startsWith("LLM bağlamı taştı"));
        assertThat(result.history()).singleElement().satisfies(attempt -> {
            assertThat(attempt.rawOutput()).isEqualTo("{uydurma");
            assertThat(attempt.error()).startsWith("LLM bağlamı taştı");
        });
        verify(client, times(1)).extract(eq("metin"), any());
    }

    @Test
    void exhaustsAfterMaxAttemptsMixingBadOutputAndTimeouts() {
        LlmOutputException bad = new LlmOutputException("LLM yanıtı çözülemedi: x", "{bozuk", null);
        when(client.extract(eq("metin"), any()))
                .thenThrow(bad)
                .thenThrow(new LlmTimeoutException("LLM zaman aşımı: HttpTimeoutException", null))
                .thenThrow(new LlmOutputException("LLM yanıtı çözülemedi: y", "{yine", null));

        LlmExtraction.Result result = extraction.extract("metin");

        assertThat(result).isInstanceOfSatisfying(LlmExtraction.Exhausted.class,
                exhausted -> assertThat(exhausted.lastError()).isEqualTo("LLM yanıtı çözülemedi: y"));
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.history()).extracting(LlmAttempt::attemptNo).containsExactly(1, 2, 3);
        // Zaman aşımı denemesinde ham çıktı yoktur.
        assertThat(result.history().get(1).rawOutput()).isNull();
        assertThat(result.history().get(1).error()).contains("zaman aşımı");
        // Zaman aşımından sonraki deneme yine son bozuk çıktıyı geri besler.
        verify(client, times(2)).extract(eq("metin"), same(bad));
    }

    @Test
    void eachAttemptTakesItsOwnPermit() {
        when(client.extract(eq("metin"), any()))
                .thenThrow(new LlmTimeoutException("t", null))
                .thenReturn(GOOD);

        extraction.extract("metin");

        verify(semaphore, times(2)).withPermit(any());
    }

    @Test
    void unreachableOllamaIsNotAnAttemptAndPropagates() {
        when(client.extract(eq("metin"), any())).thenThrow(new LlmUnavailableException("ulaşılamadı", null));

        assertThatThrownBy(() -> extraction.extract("metin")).isInstanceOf(LlmUnavailableException.class);
        verify(client, times(1)).extract(eq("metin"), any());
    }
}
