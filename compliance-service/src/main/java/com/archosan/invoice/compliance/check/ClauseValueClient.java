package com.archosan.invoice.compliance.check;

import com.archosan.invoice.compliance.ComplianceProperties;
import com.archosan.invoice.compliance.contract.ContractRepository.RetrievedChunk;
import com.archosan.invoice.llm.LlmSemaphore;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maddeden değer ve alıntı çıkarımı (FR-C2 adım 4, ADR-09): LLM'e getirilen maddeler ve tek bir soru gider, karar
 * vermez; yanıt JSON şemasıyla sınırlıdır, sıcaklık 0. Çağrı LLM semaforundan geçer ({@code sem:llm}, extraction ile
 * ortak). Ollama'ya ulaşılamaması ya da zaman aşımı istisnadır (mesaj geri konur); çözülemeyen yanıt boş döner ve
 * bulgu güvenilmez sayılır.
 */
@Component
class ClauseValueClient {

    /** Promptun ilk satırı; sahte LLM'ler (testler) uyum isteğini fatura isteğinden bununla ayırır. */
    static final String SYSTEM = """
            Sözleşme maddesi okuyucususun. Yalnızca verilen maddelere dayanarak soruyu yanıtla; tahmin etme.
            Değer maddelerde açıkça yazmıyorsa found=false ver, diğer alanları null bırak.
            Faturadaki kalem adı sözleşmede daha genel yazılmış olabilir: "Steril gazlı bez — parti 3" için
            "Steril gazlı bez" satırı, "A4 Fotokopi Kağıdı 80 gr (koli)" için "A4 Fotokopi Kağıdı 80 gr" satırı geçerlidir.
            Değer bulunduysa:
            - value: sayı (ör. 1.840,00 TL → 1840.00; 30 gün → 30),
            - unit: maddedeki birim (adet, kg, koli, gün…),
            - clauseNo: değerin yazılı olduğu madde numarası (ör. 4 ya da 4.2),
            - quote: değeri içeren cümle, maddedeki metinden harfi harfine kopyalanmış (değiştirme, kısaltma).
            Yanıt yalnızca JSON.""";

    private static final Map<String, Object> SCHEMA = schema();

    private final ChatModel chatModel;
    private final LlmSemaphore semaphore;
    private final ComplianceProperties.Llm config;
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    ClauseValueClient(ChatModel chatModel, LlmSemaphore semaphore, ComplianceProperties properties) {
        this.chatModel = chatModel;
        this.semaphore = semaphore;
        this.config = properties.llm();
    }

    String model() {
        return config.model();
    }

    /** @return çözülemeyen yanıtta boş */
    Optional<ClauseValue> ask(String question, List<RetrievedChunk> clauses) {
        StringBuilder user = new StringBuilder("Maddeler:\n\n");
        for (RetrievedChunk clause : clauses) {
            user.append("[Madde ").append(clause.clauseNo() == null ? "-" : clause.clauseNo()).append("]\n")
                    .append(clause.content()).append("\n\n");
        }
        user.append("Soru: ").append(question);
        Prompt prompt = new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(user.toString())),
                OllamaChatOptions.builder()
                        .model(config.model())
                        .temperature(0.0)
                        .numCtx(config.numCtx())
                        .numPredict(config.maxOutputTokens())
                        .format(SCHEMA)
                        .build());
        ChatResponse response = semaphore.withPermit(() -> chatModel.call(prompt));
        String raw = response == null || response.getResult() == null
                ? null
                : response.getResult().getOutput().getText();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readValue(raw, ClauseValue.class));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("found", Map.of("type", "boolean"));
        properties.put("value", Map.of("type", List.of("number", "null")));
        properties.put("unit", Map.of("type", List.of("string", "null"), "maxLength", 32));
        properties.put("clauseNo", Map.of("type", List.of("string", "null"), "maxLength", 16));
        properties.put("quote", Map.of("type", List.of("string", "null"), "maxLength", 600));
        return Map.of("type", "object", "properties", properties,
                "required", List.of("found", "value", "unit", "clauseNo", "quote"));
    }
}
