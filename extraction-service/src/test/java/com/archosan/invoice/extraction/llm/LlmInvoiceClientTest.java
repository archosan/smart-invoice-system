package com.archosan.invoice.extraction.llm;

import com.archosan.invoice.extraction.ExtractionProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Gerçek Ollama yok (CLAUDE.md); {@link ChatModel} stub'dır. */
class LlmInvoiceClientTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final LlmInvoiceClient client = new LlmInvoiceClient(chatModel, ExtractionProperties.of(Path.of("/x"), 20,
            new ExtractionProperties.Llm("qwen2.5:7b-instruct", Duration.ofSeconds(1), 2, 1, Duration.ofSeconds(1), 3,
                    Duration.ofSeconds(30), 8192, 4096, "v3")));

    @Test
    void sendsPromptV3WithSchemaZeroTemperatureContextWindowAndConfiguredModel() {
        respond("""
                {"supplierName":"ACME","supplierVkn":"1234567890","invoiceNo":"F-1","invoiceDate":"01.09.2026",
                 "dueDate":"01.10.2026","lines":[{"description":"X","quantity":"1","unitPrice":"10,00 TL",
                 "vatRate":"%20"}],"subtotal":"10,00 TL","vatTotal":"2,00 TL","grandTotal":"12,00 TL",
                 "currency":"TL","fazlalik":"yok sayılır"}
                """);

        LlmAnswer answer = client.extract("Fatura No: F-1 ...");
        LlmInvoice invoice = answer.invoice();
        assertThat(answer.rawOutput()).contains("\"fazlalik\"");

        assertThat(invoice.grandTotal()).isEqualTo("12,00 TL");
        assertThat(invoice.lines()).singleElement().extracting(LlmInvoice.Line::vatRate).isEqualTo("%20");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        OllamaChatOptions options = (OllamaChatOptions) prompt.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("qwen2.5:7b-instruct");
        assertThat(options.getTemperature()).isZero();
        assertThat(options.getNumCtx()).isEqualTo(8192);
        assertThat(options.getNumPredict()).isEqualTo(4096);
        assertThat(options.getFormat()).isEqualTo(InvoiceSchema.schema());
        assertThat(prompt.getValue().getInstructions()).extracting(m -> m.getMessageType())
                .containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(prompt.getValue().getInstructions().get(0).getText())
                .contains("BASILDIĞI GİBİ", "SAYIN", "açıklamanın parçasıdır")
                .doesNotContain("HEMEN ÖNCEKİ");
        assertThat(prompt.getValue().getInstructions().get(1).getText()).contains("Fatura No: F-1");
        assertThat(client.promptVersion()).isEqualTo("v3");
    }

    @Test
    void earlierPromptVersionCanBeSelectedAndUnknownIsRejected() {
        assertThat(InvoicePrompt.of("v1").system()).doesNotContain("HEMEN ÖNCEKİ", "açıklamanın parçasıdır");
        assertThat(InvoicePrompt.of("v2").system()).contains("HEMEN ÖNCEKİ");
        assertThatThrownBy(() -> InvoicePrompt.of("v9")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v9");
    }

    /** B-29: Ollama bağlamı dolunca başını atar ve devam eder; çıktı fatura metnini görmeden üretilmiş olabilir. */
    @Test
    void outputThatFilledTheContextWindowIsRejected() {
        respond("{\"supplierName\":\"ACME\"}", 3000, 5192);

        assertThatThrownBy(() -> client.extract("metin"))
                .isInstanceOfSatisfying(LlmOutputLimitException.class, e -> {
                    assertThat(e.getMessage()).contains("3000", "5192", "8192");
                    assertThat(e.rawOutput()).isEqualTo("{\"supplierName\":\"ACME\"}");
                });
    }

    /** B-29: prompt v2 ile 5. ve 6. faturada model tekrar döngüsüne girdi; sınırda kesilen yanıt yarımdır. */
    @Test
    void outputCutAtGenerationLimitIsRejected() {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"supplierName\":\"ACME\",\"lines\":[{\"description\":\"aaaa"),
                ChatGenerationMetadata.builder().finishReason("length").build()))));

        assertThatThrownBy(() -> client.extract("metin"))
                .isInstanceOfSatisfying(LlmOutputLimitException.class, e -> {
                    assertThat(e.getMessage()).contains("üretim sınırında", "4096");
                    assertThat(e.rawOutput()).startsWith("{\"supplierName\"");
                });
    }

    @Test
    void outputJustBelowContextWindowIsAccepted() {
        respond("{\"supplierName\":\"ACME\"}", 3000, 5191);

        assertThat(client.extract("metin").invoice().supplierName()).isEqualTo("ACME");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaRequiresEveryFieldAsStringAndLinesArray() {
        var schema = InvoiceSchema.schema();

        assertThat((List<Object>) schema.get("required")).contains("supplierVkn", "grandTotal", "lines");
        assertThat(schema.toString()).doesNotContain("number", "integer");
    }

    /** B-29: her metin alanının uzunluğu sınırlı; model bir alanın içinde tekrar döngüsüne giremez. */
    @Test
    @SuppressWarnings("unchecked")
    void everyStringFieldHasMaxLength() {
        var properties = (Map<String, Map<String, Object>>) InvoiceSchema.schema().get("properties");
        var lines = properties.get("lines");
        var lineProperties = (Map<String, Map<String, Object>>) ((Map<String, Object>) lines.get("items"))
                .get("properties");

        assertThat(lines.get("maxItems")).isEqualTo(300);
        assertThat(properties).allSatisfy((name, field) -> {
            if (!name.equals("lines")) {
                assertThat(field).as(name).containsKey("maxLength");
            }
        });
        assertThat(lineProperties).allSatisfy((name, field) -> assertThat(field).as(name).containsKey("maxLength"));
        assertThat(lineProperties.get("vatRate").get("maxLength")).isEqualTo(10);
    }

    @Test
    void unparsableOutputKeepsRawForDebugging() {
        respond("Elbette! İşte fatura: {bozuk");

        assertThatThrownBy(() -> client.extract("metin"))
                .isInstanceOfSatisfying(LlmOutputException.class,
                        e -> assertThat(e.rawOutput()).isEqualTo("Elbette! İşte fatura: {bozuk"));
    }

    @Test
    void emptyOutputIsOutputError() {
        respond("  ");

        assertThatThrownBy(() -> client.extract("metin")).isInstanceOf(LlmOutputException.class);
    }

    @Test
    void connectionErrorIsNotTreatedAsOutputError() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("Connection refused"));

        assertThatThrownBy(() -> client.extract("metin"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(LlmOutputException.class);
    }

    private void respond(String text) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    private void respond(String text, int promptTokens, int completionTokens) {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, completionTokens)).build()));
    }
}
