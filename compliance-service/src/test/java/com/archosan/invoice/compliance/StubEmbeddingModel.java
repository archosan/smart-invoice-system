package com.archosan.invoice.compliance;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Testlerin embedding modeli (gerçek Ollama yok, CLAUDE.md): sözcük torbası, her sözcük hash'iyle 1024 boyuttan birine
 * düşer. Anlamsal değildir ama ortak sözcük taşıyan metinler birbirine yakındır; "Rulman birim fiyat" sorgusu rulman
 * maddesini bulur. Hata ve yanlış boyut senaryoları için ayarlanabilir.
 */
public final class StubEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1024;
    private static final Locale TR = Locale.forLanguageTag("tr");

    private volatile RuntimeException failure;
    private volatile int dimensions = DIMENSIONS;
    private final AtomicInteger calls = new AtomicInteger();

    /** Her çağrı bu istisnayı atar ({@code null}: normal). */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void dimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        failure = null;
        dimensions = DIMENSIONS;
        calls.set(0);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        RuntimeException current = failure;
        if (current != null) {
            throw current;
        }
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vector(texts.get(i), dimensions), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText(), dimensions);
    }

    /** Normalize edilmemiş sözcük torbası (normalizasyonu servis yapar). */
    public static float[] vector(String text, int dimensions) {
        float[] vector = new float[dimensions];
        for (String word : text.toLowerCase(TR).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                vector[Math.floorMod(word.hashCode(), dimensions)] += 1;
            }
        }
        if (dimensions > 0 && allZero(vector)) {
            vector[0] = 1;
        }
        return vector;
    }

    private static boolean allZero(float[] vector) {
        for (float v : vector) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }
}
