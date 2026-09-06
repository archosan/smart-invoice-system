package com.archosan.invoice.messaging;

import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.InvoiceLine;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.archosan.invoice.messaging.message.RpaCompleted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceMessageConverterTest {

    private static final UUID MESSAGE_ID = UUID.fromString("5c7e2a90-1b3d-4f6a-8c2e-9d0f1a2b3c4d");

    private final InvoiceMessageConverter converter = new InvoiceMessageConverter();
    private final JsonMapper plainMapper = JsonMapper.builder().build();

    static Iterable<InvoiceMessage> allMessages() {
        return SampleMessages.all();
    }

    @ParameterizedTest
    @MethodSource("allMessages")
    void roundTripsEveryMessageType(InvoiceMessage payload) {
        MessageType type = MessageType.of(payload);
        MessageEnvelope envelope = MessageEnvelope.of(MESSAGE_ID, SampleMessages.DOCUMENT_ID, type);

        ReceivedMessage received = converter.fromAmqpMessage(converter.toAmqpMessage(envelope, payload));

        assertThat(received.envelope()).isEqualTo(envelope);
        assertThat(received.payload()).isEqualTo(payload);
    }

    @Test
    void writesEnvelopeIntoAmqpProperties() {
        MessageEnvelope envelope = MessageEnvelope.of(MESSAGE_ID, SampleMessages.DOCUMENT_ID,
                MessageType.EXTRACT_INVOICE);

        MessageProperties props = converter.toAmqpMessage(envelope, SampleMessages.extractInvoice())
                .getMessageProperties();

        assertThat(props.getMessageId()).isEqualTo(MESSAGE_ID.toString());
        assertThat(props.getCorrelationId()).isEqualTo(SampleMessages.DOCUMENT_ID.toString());
        assertThat(props.getType()).isEqualTo("ExtractInvoice");
        assertThat(props.<Integer>getHeader("x-schema-version")).isEqualTo(1);
        assertThat(props.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(props.getContentType()).isEqualTo("application/json");
        assertThat(props.getHeaders()).doesNotContainKey("__TypeId__");
    }

    @Test
    void writesAmountsAsStringsAndDatesAsIso() {
        JsonNode json = plainMapper.readTree(converter.toJson(SampleMessages.extractionCompleted()));

        assertThat(json.get("confidenceScore").isString()).isTrue();
        assertThat(json.get("confidenceScore").asString()).isEqualTo("0.92");
        JsonNode fields = json.get("fields");
        assertThat(fields.get("grandTotal").asString()).isEqualTo("180.00");
        assertThat(fields.get("grandTotal").isString()).isTrue();
        assertThat(fields.get("invoiceDate").asString()).isEqualTo("2026-09-30");
        JsonNode line = fields.get("lines").get(0);
        assertThat(line.get("quantity").isString()).isTrue();
        assertThat(line.get("quantity").asString()).isEqualTo("1.5");
        assertThat(line.get("unitPrice").asString()).isEqualTo("100.00");
        assertThat(line.get("vatRate").isInt()).isTrue();
    }

    @Test
    void writesAmountsInPlainNotation() {
        var payload = new CheckCompliance(SampleMessages.DOCUMENT_ID, null, null, null,
                List.of(new InvoiceLine("x", BigDecimal.ONE, new BigDecimal("1E+3"), 20)), null, null, null, "TRY");

        JsonNode json = plainMapper.readTree(converter.toJson(payload));

        assertThat(json.get("lines").get(0).get("unitPrice").asString()).isEqualTo("1000");
    }

    @Test
    void ignoresUnknownFields() {
        Message message = amqpMessage("RpaCompleted", """
                {"documentId":"%s","portalRefNo":"4711","foundExisting":true,"addedInV1_1":"x"}
                """.formatted(SampleMessages.DOCUMENT_ID), props -> { });

        assertThat(converter.fromAmqpMessage(message).payload())
                .isEqualTo(new RpaCompleted(SampleMessages.DOCUMENT_ID, "4711", true));
    }

    @Test
    void acceptsSchemaVersionHeaderAsString() {
        Message message = validExtractInvoice(props -> props.setHeader("x-schema-version", "1"));

        assertThat(converter.fromAmqpMessage(message).envelope().schemaVersion()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownType() {
        assertPoison(amqpMessage("DeleteEverything", "{}", props -> { }), "Bilinmeyen mesaj tipi");
    }

    @Test
    void rejectsMissingType() {
        assertPoison(validExtractInvoice(props -> props.setType(null)), "Bilinmeyen mesaj tipi");
    }

    @Test
    void rejectsMissingMessageId() {
        assertPoison(validExtractInvoice(props -> props.setMessageId(null)), "message_id");
    }

    @Test
    void rejectsInvalidCorrelationId() {
        assertPoison(validExtractInvoice(props -> props.setCorrelationId("not-a-uuid")), "correlation_id");
    }

    @Test
    void rejectsMissingSchemaVersion() {
        assertPoison(validExtractInvoice(props -> props.getHeaders().remove("x-schema-version")),
                "x-schema-version");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertPoison(validExtractInvoice(props -> props.setHeader("x-schema-version", 2)), "şema sürümü");
    }

    @Test
    void rejectsNonJsonContentType() {
        assertPoison(validExtractInvoice(props -> props.setContentType("text/plain")), "content_type");
    }

    @Test
    void rejectsMalformedJson() {
        assertPoison(amqpMessage("ExtractInvoice", "{\"documentId\":", props -> { }), "Gövde çözümlenemedi");
    }

    @Test
    void rejectsPayloadWithoutDocumentId() {
        assertPoison(amqpMessage("ExtractInvoice", "{\"storageUri\":\"file:///x.pdf\"}", props -> { }),
                "Gövde çözümlenemedi");
    }

    @Test
    void rejectsNullBody() {
        assertPoison(amqpMessage("ExtractInvoice", "null", props -> { }), "Boş gövde");
    }

    @Test
    void refusesEnvelopeThatDoesNotMatchPayload() {
        MessageEnvelope envelope = MessageEnvelope.of(MESSAGE_ID, SampleMessages.DOCUMENT_ID,
                MessageType.RPA_COMPLETED);

        assertThatThrownBy(() -> converter.toAmqpMessage(envelope, SampleMessages.extractInvoice()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertPoison(Message message, String reason) {
        assertThatThrownBy(() -> converter.fromAmqpMessage(message))
                .isInstanceOf(MessageConversionException.class)
                .hasMessageContaining(reason);
    }

    private Message validExtractInvoice(Consumer<MessageProperties> customizer) {
        ExtractInvoice payload = SampleMessages.extractInvoice();
        return amqpMessage("ExtractInvoice", converter.toJson(payload), customizer);
    }

    private Message amqpMessage(String type, String json, Consumer<MessageProperties> customizer) {
        MessageEnvelope envelope = MessageEnvelope.of(MESSAGE_ID, SampleMessages.DOCUMENT_ID,
                MessageType.EXTRACT_INVOICE);
        Message message = converter.toAmqpMessage(envelope, json);
        message.getMessageProperties().setType(type);
        customizer.accept(message.getMessageProperties());
        return new Message(json.getBytes(StandardCharsets.UTF_8), message.getMessageProperties());
    }
}
