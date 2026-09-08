package com.archosan.invoice.compliance;

import com.archosan.invoice.compliance.testdata.ClauseAnswerer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Testlerin sohbet modeli (gerçek Ollama yok): varsayılan olarak {@link ClauseAnswerer} ("iyi LLM"); halüsinasyon ve
 * bozuk çıktı senaryoları için yanıt değiştirilebilir ya da hata atılabilir.
 */
public final class StubChatModel implements ChatModel {

    private final AtomicReference<UnaryOperator<String>> answers = new AtomicReference<>(UnaryOperator.identity());
    private volatile RuntimeException failure;
    private final List<String> prompts = new CopyOnWriteArrayList<>();

    /** İyi LLM'in yanıtını dönüştürür (ör. alıntıyı bozmak); {@code identity} varsayılan. */
    public void rewrite(UnaryOperator<String> rewrite) {
        answers.set(rewrite);
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    public void reset() {
        answers.set(UnaryOperator.identity());
        failure = null;
        prompts.clear();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String text = prompt.getInstructions().stream().map(Message::getText).collect(Collectors.joining("\n"));
        prompts.add(text);
        RuntimeException current = failure;
        if (current != null) {
            throw current;
        }
        String answer = answers.get().apply(ClauseAnswerer.answer(text));
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }
}
