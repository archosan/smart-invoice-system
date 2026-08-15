package com.archosan.invoice.extraction.llm;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.llm.LlmSemaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sınırlı yeniden deneme (B-19, US-05): en fazla {@code max-attempts} deneme; bozuk çıktı ve zaman aşımı bütçeden
 * düşer, bozuk çıktının hatası ve ham yanıtı bir sonraki denemeye geri beslenir. Semafor izni deneme başına alınır,
 * denemeler arasında bırakılır. Ollama'ya ulaşılamaması ({@link LlmUnavailableException}) deneme sayılmaz, olduğu
 * gibi yukarı çıkar (A1).
 */
@Component
public class LlmExtraction {

    private static final Logger log = LoggerFactory.getLogger(LlmExtraction.class);

    /** Denemenin sonucu; {@code history} her denemenin izidir ({@code extraction_runs}, B-21). */
    public sealed interface Result {

        List<LlmAttempt> history();

        default int attempts() {
            return history().size();
        }
    }

    public record Succeeded(LlmInvoice invoice, List<LlmAttempt> history) implements Result {
    }

    /** @param lastError son denemenin hatası; {@code ExtractionFailed.detail} olur */
    public record Exhausted(String lastError, List<LlmAttempt> history) implements Result {
    }

    private final LlmInvoiceClient client;
    private final LlmSemaphore semaphore;
    private final int maxAttempts;

    public LlmExtraction(LlmInvoiceClient client, LlmSemaphore semaphore, ExtractionProperties properties) {
        this.client = client;
        this.semaphore = semaphore;
        this.maxAttempts = properties.llm().maxAttempts();
    }

    public Result extract(String invoiceText) {
        List<LlmAttempt> history = new ArrayList<>();
        LlmOutputException previousOutput = null;
        String lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LlmOutputException feedback = previousOutput;
            long start = System.nanoTime();
            try {
                LlmAnswer answer = semaphore.withPermit(() -> client.extract(invoiceText, feedback));
                history.add(new LlmAttempt(attempt, since(start), answer.rawOutput(), null));
                return new Succeeded(answer.invoice(), List.copyOf(history));
            } catch (LlmOutputException e) {
                previousOutput = e;
                lastError = e.getMessage();
                history.add(new LlmAttempt(attempt, since(start), e.rawOutput(), lastError));
            } catch (LlmOutputLimitException e) {
                // Aynı istem yine taşar ya da yine döngüye girer; kalan denemeler boşa gider (B-29).
                lastError = e.getMessage();
                history.add(new LlmAttempt(attempt, since(start), e.rawOutput(), lastError));
                log.warn("LLM denemesi {}/{} başarısız, yeniden denenmiyor: {}", attempt, maxAttempts, lastError);
                return new Exhausted(lastError, List.copyOf(history));
            } catch (LlmTimeoutException e) {
                // Zaman aşımında geri beslenecek çıktı yok; önceki bozuk çıktının geri beslemesi korunur.
                lastError = e.getMessage();
                history.add(new LlmAttempt(attempt, since(start), null, lastError));
            }
            log.warn("LLM denemesi {}/{} başarısız: {}", attempt, maxAttempts, lastError);
        }
        return new Exhausted(lastError, List.copyOf(history));
    }

    private static Duration since(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
