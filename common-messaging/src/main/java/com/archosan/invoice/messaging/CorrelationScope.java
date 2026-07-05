package com.archosan.invoice.messaging;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Log satırlarına {@code correlationId} (= documentId), {@code messageId} ve {@code messageType} koyar (NFR-06).
 * try-with-resources ile kullanılır; kapanınca MDC açılmadan önceki haline döner, iç içe kullanılabilir.
 *
 * <pre>{@code
 * try (var ignored = CorrelationScope.open(documentId)) {
 *     log.info("Yükleme alındı");   // {"correlationId": "…", …}
 * }
 * }</pre>
 *
 * Dinleyici ve relay bunu kendileri açar; servis kodu yalnızca kendi giriş noktalarında (REST) kullanır.
 */
public final class CorrelationScope implements AutoCloseable {

    public static final String CORRELATION_ID = "correlationId";
    public static final String MESSAGE_ID = "messageId";
    public static final String MESSAGE_TYPE = "messageType";

    private static final String[] KEYS = {CORRELATION_ID, MESSAGE_ID, MESSAGE_TYPE};

    private final Map<String, String> previous = new HashMap<>();

    private CorrelationScope(String correlationId, String messageId, String messageType) {
        for (String key : KEYS) {
            previous.put(key, MDC.get(key));
        }
        put(CORRELATION_ID, correlationId);
        put(MESSAGE_ID, messageId);
        put(MESSAGE_TYPE, messageType);
    }

    public static CorrelationScope open(UUID correlationId) {
        return new CorrelationScope(String.valueOf(correlationId), null, null);
    }

    /**
     * Mesaj işlenirken kullanılır. Değerler ham AMQP alanlarından gelebilir (zehirli mesaj); {@code null} olan
     * anahtar değiştirilmez.
     */
    public static CorrelationScope forMessage(String correlationId, String messageId, String messageType) {
        return new CorrelationScope(correlationId, messageId, messageType);
    }

    @Override
    public void close() {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }

    private static void put(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
