package com.archosan.invoice.extraction.testdata;

import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Testler için LLM (NFR-12, B-21; CLAUDE.md: testlerde gerçek Ollama yok). Spring AI'ın {@link ChatModel}'ini uygular,
 * {@code LlmInvoiceClient} onu fark etmez. Varsayılan davranış iyi bir LLM'dir: metindeki sentetik faturanın basılı
 * değerlerini döndürür. Sıradaki çağrılar için farklı cevaplar sıralanabilir.
 *
 * <p>Zaman aşımı ve bağlantı hatası, Spring'in HTTP katmanının (Reactor Netty) gerçekte fırlattığı biçimde
 * sarmalanır. HTTP düzeyinin kendisini sınamak için {@code FakeOllamaServer} vardır.
 */
public final class StubChatModel implements ChatModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** İstekteki son kullanıcı mesajından (fatura metni veya geri besleme) bir cevap üretir. */
    @FunctionalInterface
    public interface Behavior extends Function<Prompt, String> {
    }

    /** Metindeki sentetik faturanın basılı değerleri; fatura tanınmazsa boş nesne. */
    public static final Behavior GOOD = prompt -> PrintedInvoices.findByText(fullText(prompt))
            .map(invoice -> JSON.writeValueAsString(PrintedInvoices.asPrinted(invoice)))
            .orElse("{}");

    public static Behavior answer(String raw) {
        return prompt -> raw;
    }

    public static final Behavior TIMEOUT = prompt -> {
        throw new ResourceAccessException("I/O error on POST request",
                new IOException(ReadTimeoutException.INSTANCE));
    };

    public static final Behavior UNREACHABLE = prompt -> {
        throw new ResourceAccessException("I/O error on POST request", new ConnectException("Connection refused"));
    };

    private final ConcurrentLinkedDeque<Behavior> script = new ConcurrentLinkedDeque<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private volatile Behavior fallback = GOOD;

    /** Sıradaki çağrılar için; bitince {@link #always} geçerlidir. */
    public StubChatModel then(Behavior... behaviors) {
        script.addAll(List.of(behaviors));
        return this;
    }

    public StubChatModel always(Behavior behavior) {
        fallback = behavior;
        return this;
    }

    public void reset() {
        script.clear();
        prompts.clear();
        fallback = GOOD;
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Behavior behavior = script.isEmpty() ? fallback : script.poll();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(behavior.apply(prompt)))));
    }

    private static String fullText(Prompt prompt) {
        return String.join("\n", prompt.getInstructions().stream().map(m -> m.getText()).toList());
    }
}
