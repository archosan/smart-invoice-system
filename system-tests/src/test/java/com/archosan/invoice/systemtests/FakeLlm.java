package com.archosan.invoice.systemtests;

import com.archosan.invoice.compliance.testdata.ClauseAnswerer;
import com.archosan.invoice.extraction.ollama.FakeOllamaServer;
import com.archosan.invoice.extraction.testdata.PrintedInvoices;
import com.archosan.invoice.extraction.testdata.SyntheticInvoice;
import com.archosan.invoice.extraction.testdata.SyntheticInvoices;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Sistem testlerinin Ollama'sı (CLAUDE.md: testlerde gerçek Ollama yok): HTTP düzeyinde sahte sunucu, iyi bir LLM gibi
 * istekteki faturanın basılı değerlerini döndürür (B-16, {@link PrintedInvoices}); uyum isteğinde maddeden değeri ve
 * alıntıyı ({@link ClauseAnswerer}, B-46), embedding isteğinde sözcük torbası vektörünü verir. Faturaya özel bozuk çıktı
 * ve genel gecikme ayarlanabilir. Konteynerler erişebilsin diye {@code 0.0.0.0}'da dinler.
 */
final class FakeLlm {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FakeOllamaServer server = new FakeOllamaServer("0.0.0.0");
    private final List<SyntheticInvoice> extraInvoices = new CopyOnWriteArrayList<>();
    private final Map<String, String> rawAnswers = new ConcurrentHashMap<>();
    private volatile Duration delay = Duration.ZERO;

    void start() {
        server.start();
        apply();
    }

    void stop() {
        server.stop();
    }

    int port() {
        return server.port();
    }

    /** B-16'da olmayan, testin ürettiği bir fatura da tanınsın. */
    void know(SyntheticInvoice invoice) {
        extraInvoices.add(invoice);
    }

    /** Bu fatura numarası için her istekte {@code raw} dönülür (US-05). */
    void answerRaw(String invoiceNo, String raw) {
        rawAnswers.put(invoiceNo, raw);
    }

    /** Her yanıt bu kadar gecikir (çökme testlerinde LLM çağrısı sürerken öldürmek için). */
    void delay(Duration delay) {
        this.delay = delay;
        apply();
    }

    /** Sayılan sohbet istekleri; bir isteğin geldiğini beklemek için. */
    int requestCount() {
        return server.chatRequests().size();
    }

    void reset() {
        rawAnswers.clear();
        delay(Duration.ZERO);
    }

    private void apply() {
        server.always(new FakeOllamaServer.Reply(delay, this::answer));
    }

    private String answer(String requestBody) {
        if (requestBody.contains(ClauseAnswerer.MARKER)) {
            return ClauseAnswerer.answer(promptText(requestBody));
        }
        Optional<SyntheticInvoice> invoice = Stream.concat(SyntheticInvoices.all().stream(), extraInvoices.stream())
                .filter(i -> requestBody.contains(i.fields().invoiceNo()))
                .findFirst();
        if (invoice.isEmpty()) {
            return "{}";
        }
        String raw = rawAnswers.get(invoice.get().fields().invoiceNo());
        return raw != null ? raw : JSON.writeValueAsString(PrintedInvoices.asPrinted(invoice.get()));
    }

    /** Uyum isteği (B-46): mesajların metni; sözleşme maddelerinden "iyi LLM" yanıtı ({@link ClauseAnswerer}). */
    private static String promptText(String requestBody) {
        StringBuilder text = new StringBuilder();
        JSON.readTree(requestBody).path("messages").forEach(m -> text.append(m.path("content").asString()).append('\n'));
        return text.toString();
    }
}
