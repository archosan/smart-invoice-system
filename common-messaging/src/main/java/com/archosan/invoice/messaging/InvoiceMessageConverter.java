package com.archosan.invoice.messaging;

import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Zarf ve gövdeyi AMQP mesajına çevirir ve geri çözer.
 *
 * <p>Tip bilgisi Java sınıf adıyla değil, AMQP {@code type} alanındaki mesaj adıyla taşınır. Tutarlar
 * ({@link BigDecimal}) JSON'da string olarak yazılır. Tüketici bilinmeyen alanları yok sayar.
 *
 * <p>Çözümlenemeyen her mesaj (bilinmeyen tip, eksik zarf alanı, desteklenmeyen şema sürümü, bozuk JSON)
 * {@link MessageConversionException} fırlatır; tüketici bunu zehirli mesaj sayar ve doğrudan DLQ'ya gönderir.
 */
public final class InvoiceMessageConverter {

    public static final String SCHEMA_VERSION_HEADER = "x-schema-version";

    private final JsonMapper mapper;

    public InvoiceMessageConverter() {
        this.mapper = JsonMapper.builder()
                .withConfigOverride(BigDecimal.class,
                        o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Gövdeyi JSON'a çevirir; outbox'ın {@code payload} kolonuna yazılan değer budur. */
    public String toJson(InvoiceMessage payload) {
        return mapper.writeValueAsString(payload);
    }

    /**
     * Mesaj parçalarını (ör. {@code InvoiceFields}) mesajlarla aynı kurallarla JSON'a çevirir; servisler JSONB
     * kolonlarında kullanır, böylece tutarlar orada da string kalır.
     */
    public String writeJson(Object value) {
        return mapper.writeValueAsString(value);
    }

    /** {@link #writeJson} ile yazılmış JSON'u okur. */
    public <T> T readJson(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    /** Gövdeyi serileştirip AMQP mesajı üretir. */
    public Message toAmqpMessage(MessageEnvelope envelope, InvoiceMessage payload) {
        if (!envelope.type().payloadClass().isInstance(payload)) {
            throw new IllegalArgumentException(
                    "Zarf tipi " + envelope.type().typeName() + ", gövde " + payload.getClass().getSimpleName());
        }
        return toAmqpMessage(envelope, toJson(payload));
    }

    /** Hazır JSON gövdeden AMQP mesajı üretir; relay outbox satırını böyle yayınlar. */
    public Message toAmqpMessage(MessageEnvelope envelope, String json) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(envelope.messageId().toString());
        props.setCorrelationId(envelope.correlationId().toString());
        props.setType(envelope.type().typeName());
        props.setHeader(SCHEMA_VERSION_HEADER, envelope.schemaVersion());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    public ReceivedMessage fromAmqpMessage(Message message) {
        MessageProperties props = message.getMessageProperties();

        MessageType type = MessageType.fromTypeName(props.getType())
                .orElseThrow(() -> new MessageConversionException("Bilinmeyen mesaj tipi: " + props.getType()));
        UUID messageId = parseUuid(props.getMessageId(), "message_id");
        UUID correlationId = parseUuid(props.getCorrelationId(), "correlation_id");
        int schemaVersion = parseSchemaVersion(props.getHeader(SCHEMA_VERSION_HEADER));
        if (schemaVersion != type.schemaVersion()) {
            throw new MessageConversionException("Desteklenmeyen şema sürümü: " + type.typeName()
                    + " v" + schemaVersion + ", beklenen v" + type.schemaVersion());
        }
        String contentType = props.getContentType();
        if (contentType == null || !contentType.startsWith(MessageProperties.CONTENT_TYPE_JSON)) {
            throw new MessageConversionException("Beklenmeyen content_type: " + contentType);
        }

        InvoiceMessage payload;
        try {
            payload = mapper.readValue(message.getBody(), type.payloadClass());
        } catch (JacksonException e) {
            throw new MessageConversionException("Gövde çözümlenemedi: " + type.typeName(), e);
        }
        if (payload == null) {
            throw new MessageConversionException("Boş gövde: " + type.typeName());
        }
        return new ReceivedMessage(new MessageEnvelope(messageId, correlationId, type, schemaVersion), payload);
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null) {
            throw new MessageConversionException("Eksik zarf alanı: " + field);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new MessageConversionException("Geçersiz " + field + ": " + value, e);
        }
    }

    private static int parseSchemaVersion(Object header) {
        return switch (header) {
            case null -> throw new MessageConversionException("Eksik başlık: " + SCHEMA_VERSION_HEADER);
            case Number n -> n.intValue();
            case String s -> {
                try {
                    yield Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    throw new MessageConversionException("Geçersiz " + SCHEMA_VERSION_HEADER + ": " + s, e);
                }
            }
            default -> throw new MessageConversionException("Geçersiz " + SCHEMA_VERSION_HEADER + ": " + header);
        };
    }
}
