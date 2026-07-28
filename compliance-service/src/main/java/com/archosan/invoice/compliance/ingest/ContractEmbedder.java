package com.archosan.invoice.compliance.ingest;

import com.archosan.invoice.compliance.ComplianceProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Chunk embedding'i (FR-C1 adım 5): bge-m3, toplu istek, L2 normalize (kosinüs aramasında {@code <=>} ile iç çarpım
 * aynı sırayı verir). Sonuç pgvector metni ({@code [0.1,…]}); kütüphane gerekmez, {@code CAST(… AS vector)} ile
 * yazılır. Beklenen boyuttan farklı vektör kalıcı hatadır (yanlış model): mesaj doğrudan DLQ'ya gider. Ollama'ya
 * ulaşılamaması geçicidir, mesaj geri konur. LLM semaforuna girmez (B-45: kısa ve seyrek iş; B-46'da gözden geçirilir).
 */
@Component
public class ContractEmbedder {

    private final EmbeddingModel model;
    private final ComplianceProperties.Embedding config;

    ContractEmbedder(EmbeddingModel model, ComplianceProperties properties) {
        this.model = model;
        this.config = properties.embedding();
    }

    public String modelName() {
        return config.model();
    }

    /** Tek sorgu metni (B-46, kontrol başına sorgu); chunk'larla aynı model ve normalizasyon. */
    public String embedQuery(String text) {
        return embed(List.of(text)).getFirst();
    }

    List<String> embed(List<String> texts) {
        List<String> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += config.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + config.batchSize()));
            List<float[]> embeddings = model.embed(batch);
            if (embeddings.size() != batch.size()) {
                throw new IllegalStateException("Embedding sayısı " + embeddings.size() + " ≠ " + batch.size());
            }
            for (float[] embedding : embeddings) {
                vectors.add(toVector(embedding));
            }
        }
        return vectors;
    }

    private String toVector(float[] embedding) {
        if (embedding.length != config.dimensions()) {
            throw new AmqpRejectAndDontRequeueException("Embedding boyutu " + embedding.length + " ≠ beklenen "
                    + config.dimensions() + "; model doğru mu (" + config.model() + ")?");
        }
        double norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            throw new AmqpRejectAndDontRequeueException("Sıfır vektör döndü (" + config.model() + ")");
        }
        StringJoiner vector = new StringJoiner(",", "[", "]");
        for (float v : embedding) {
            vector.add(Float.toString((float) (v / norm)));
        }
        return vector.toString();
    }
}
