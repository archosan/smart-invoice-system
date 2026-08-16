package com.archosan.invoice.extraction.llm;

import com.archosan.invoice.extraction.ExtractionProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Tek bir LLM denemesi (FR-E2, FR-E5): ayarlı prompt sürümü ({@link InvoicePrompt}), sıcaklık 0, sabit bağlam
 * penceresi ({@code num_ctx}), Ollama'ya JSON şeması ({@code format}). Sonuçlar:
 * <ul>
 *   <li>çözülen çıktı {@link LlmInvoice},</li>
 *   <li>çözülemeyen çıktı {@link LlmOutputException} (ham yanıtla; bir sonraki denemeye geri beslenir),</li>
 *   <li>bağlam penceresinin dolması veya çıktının üretim sınırında ({@code num_predict}) kesilmesi
 *       {@link LlmOutputLimitException} (çıktı güvenilmez, yeniden denenmez),</li>
 *   <li>{@code spring.http.clients.read-timeout} aşımı {@link LlmTimeoutException},</li>
 *   <li>Ollama'ya ulaşılamaması {@link LlmUnavailableException} (deneme sayılmaz, A1),</li>
 *   <li>diğer hatalar olduğu gibi.</li>
 * </ul>
 * Spring AI'ın kendi yeniden denemesi kapalıdır ({@code spring.ai.retry.max-attempts: 1}); denemeyi
 * {@code LlmExtraction} yönetir.
 */
@Component
public class LlmInvoiceClient {

    private final ChatModel chatModel;
    private final String model;
    private final int numCtx;
    private final int maxOutputTokens;
    private final InvoicePrompt prompt;
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public LlmInvoiceClient(ChatModel chatModel, ExtractionProperties properties) {
        this.chatModel = chatModel;
        this.model = properties.llm().model();
        this.numCtx = properties.llm().numCtx();
        this.maxOutputTokens = properties.llm().maxOutputTokens();
        this.prompt = InvoicePrompt.of(properties.llm().promptVersion());
    }

    public String model() {
        return model;
    }

    public String promptVersion() {
        return prompt.version();
    }

    public LlmAnswer extract(String invoiceText) {
        return extract(invoiceText, null);
    }

    /** @param previous önceki denemenin çözülemeyen çıktısı; verilirse hatası prompt'a geri beslenir */
    public LlmAnswer extract(String invoiceText, LlmOutputException previous) {
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage(prompt.system()), new UserMessage(prompt.user(invoiceText))));
        if (previous != null) {
            messages.add(new AssistantMessage(previous.rawOutput() == null ? "" : previous.rawOutput()));
            messages.add(new UserMessage(prompt.retry(previous.getMessage())));
        }
        Prompt request = new Prompt(messages, OllamaChatOptions.builder()
                .model(model)
                .temperature(0.0)
                .numCtx(numCtx)
                .numPredict(maxOutputTokens)
                .format(InvoiceSchema.schema())
                .build());
        ChatResponse response;
        try {
            response = chatModel.call(request);
        } catch (RuntimeException e) {
            throw switch (LlmFailures.classify(e)) {
                case UNREACHABLE -> new LlmUnavailableException("Ollama'ya ulaşılamadı: " + rootMessage(e), e);
                case TIMEOUT -> new LlmTimeoutException("LLM zaman aşımı: " + rootMessage(e), e);
                case OTHER -> e;
            };
        }
        String raw = response == null || response.getResult() == null
                ? null
                : response.getResult().getOutput().getText();
        checkContext(response, raw);
        checkLength(response, raw);
        if (raw == null || raw.isBlank()) {
            throw new LlmOutputException("LLM boş yanıt döndü", raw, null);
        }
        try {
            LlmInvoice invoice = json.readValue(raw, LlmInvoice.class);
            if (invoice == null) {
                throw new LlmOutputException("LLM yanıtı JSON nesnesi değil", raw, null);
            }
            return new LlmAnswer(invoice, raw);
        } catch (JacksonException e) {
            throw new LlmOutputException("LLM yanıtı çözülemedi: " + e.getOriginalMessage(), raw, e);
        }
    }

    /**
     * İstem + üretim token sayısı bağlam penceresine ulaştıysa Ollama bağlamın başını atmıştır (context shift);
     * çıktı fatura metnini görmeden üretilmiş olabilir (B-29). Sayılar yoksa (eski Ollama, stub) kontrol yapılmaz.
     */
    private void checkContext(ChatResponse response, String raw) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        if (promptTokens == null || completionTokens == null) {
            return;
        }
        if (promptTokens + completionTokens >= numCtx) {
            throw new LlmOutputLimitException(("LLM bağlamı taştı: istem %d + çıktı %d token ≥ num_ctx %d; "
                    + "fatura metni bağlamdan düşmüş olabilir").formatted(promptTokens, completionTokens, numCtx), raw);
        }
    }

    /** Ollama {@code done_reason=length}: çıktı {@code num_predict}'te kesildi, yanıt yarım (B-29). */
    private void checkLength(ChatResponse response, String raw) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return;
        }
        if ("length".equals(response.getResult().getMetadata().getFinishReason())) {
            throw new LlmOutputLimitException(("LLM çıktısı üretim sınırında (%d token) kesildi; yanıt yarım, model "
                    + "tekrar döngüsüne girmiş olabilir").formatted(maxOutputTokens), raw);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
