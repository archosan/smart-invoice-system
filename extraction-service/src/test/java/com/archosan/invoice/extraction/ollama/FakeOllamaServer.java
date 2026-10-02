package com.archosan.invoice.extraction.ollama;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Ollama HTTP API'sinin testler için taklidi ({@code /api/chat}, {@code /api/embed}, {@code /api/tags}). Gerçek Ollama kullanılmadan Spring
 * AI'ın gerçek {@code OllamaChatModel}'i çalıştırılır: istek gövdesi (şema, sıcaklık), okuma zaman aşımı ve bağlantı
 * reddi doğrulanabilir. Aynı portta durdurulup yeniden başlatılabilir. {@code /api/embed} her metne 1024 boyutlu
 * sözcük torbası vektörü verir (sözcük hash'i bir boyuta düşer): anlamsal değil, ama ortak sözcüklü metinler yakındır
 * (B-46, compliance sistem testleri).
 */
public final class FakeOllamaServer {

    /** {@code /api/chat}'e verilecek tek yanıt: isteğe göre içerik, isteğe bağlı gecikme. */
    public record Reply(Duration delay, Function<String, String> content) {

        public static Reply of(String content) {
            return new Reply(Duration.ZERO, request -> content);
        }
    }

    private static final int EMBEDDING_DIMENSIONS = 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String bindAddress;
    private final int port;
    private final List<String> chatRequests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Reply> script = new ConcurrentLinkedDeque<>();
    private volatile Reply fallback = Reply.of("{}");
    private HttpServer server;

    public FakeOllamaServer() {
        this("127.0.0.1");
    }

    /**
     * @param bindAddress dinlenecek adres; konteynerlerin {@code host.docker.internal} ile ulaşması için (system-tests,
     *                    B-28) {@code 0.0.0.0}
     */
    public FakeOllamaServer(String bindAddress) {
        this.bindAddress = bindAddress;
        this.port = freePort();
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/tags", exchange -> respond(exchange, "{\"models\":[]}"));
        server.createContext("/api/chat", this::chat);
        server.createContext("/api/embed", this::embed);
        server.start();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Sıradaki istekler için yanıtlar; bitince {@link #always} kullanılır. */
    public void then(Reply... replies) {
        script.addAll(List.of(replies));
    }

    public void always(Reply reply) {
        fallback = reply;
    }

    public void reset() {
        script.clear();
        chatRequests.clear();
        fallback = Reply.of("{}");
    }

    public List<String> chatRequests() {
        return List.copyOf(chatRequests);
    }

    private void chat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        chatRequests.add(body);
        Reply reply = script.isEmpty() ? fallback : script.poll();
        if (!reply.delay().isZero()) {
            try {
                Thread.sleep(reply.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        respond(exchange, chatResponse(reply.content().apply(body)));
    }

    private void embed(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        List<String> inputs = new ArrayList<>();
        JsonNode input = request.path("input");
        if (input.isArray()) {
            input.forEach(text -> inputs.add(text.asString()));
        } else {
            inputs.add(input.asString());
        }
        StringJoiner embeddings = new StringJoiner(",", "[", "]");
        for (String text : inputs) {
            StringJoiner vector = new StringJoiner(",", "[", "]");
            for (float v : bagOfWords(text)) {
                vector.add(Float.toString(v));
            }
            embeddings.add(vector.toString());
        }
        respond(exchange, "{\"model\":\"bge-m3\",\"embeddings\":" + embeddings
                + ",\"total_duration\":1,\"load_duration\":1,\"prompt_eval_count\":1}");
    }

    /** L2 normalize sözcük torbası, 1024 boyut. */
    static float[] bagOfWords(String text) {
        float[] vector = new float[EMBEDDING_DIMENSIONS];
        for (String word : text.toLowerCase(Locale.forLanguageTag("tr")).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                vector[Math.floorMod(word.hashCode(), EMBEDDING_DIMENSIONS)] += 1;
            }
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) {
            vector[0] = 1;
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / Math.sqrt(norm));
        }
        return vector;
    }

    private static String chatResponse(String content) {
        return """
                {"model":"qwen2.5:7b-instruct","created_at":"2026-10-02T10:00:00Z",
                 "message":{"role":"assistant","content":%s},"done":true,"done_reason":"stop",
                 "total_duration":1,"load_duration":1,"prompt_eval_count":1,"prompt_eval_duration":1,
                 "eval_count":1,"eval_duration":1}
                """.formatted(jsonString(content));
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException clientGone) {
            // İstemci zaman aşımıyla bağlantıyı kapatmış olabilir.
        } finally {
            exchange.close();
        }
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
