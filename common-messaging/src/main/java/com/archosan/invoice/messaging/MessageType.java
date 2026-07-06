package com.archosan.invoice.messaging;

import com.archosan.invoice.messaging.message.CheckCompliance;
import com.archosan.invoice.messaging.message.ComplianceCompleted;
import com.archosan.invoice.messaging.message.ExtractInvoice;
import com.archosan.invoice.messaging.message.ExtractionCompleted;
import com.archosan.invoice.messaging.message.ExtractionFailed;
import com.archosan.invoice.messaging.message.IngestContract;
import com.archosan.invoice.messaging.message.InvoiceMessage;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.messaging.message.RpaCompleted;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mesaj listesi (DECISIONS.md §5): AMQP {@code type} adı, gövde sınıfı, exchange, routing key ve şema sürümü.
 */
public enum MessageType {

    EXTRACT_INVOICE("ExtractInvoice", ExtractInvoice.class, Exchanges.COMMANDS, "extract.invoice"),
    EXTRACTION_COMPLETED("ExtractionCompleted", ExtractionCompleted.class, Exchanges.EVENTS, "extraction.completed"),
    EXTRACTION_FAILED("ExtractionFailed", ExtractionFailed.class, Exchanges.EVENTS, "extraction.failed"),
    CHECK_COMPLIANCE("CheckCompliance", CheckCompliance.class, Exchanges.COMMANDS, "compliance.check"),
    COMPLIANCE_COMPLETED("ComplianceCompleted", ComplianceCompleted.class, Exchanges.EVENTS, "compliance.completed"),
    POST_TO_PORTAL("PostToPortal", PostToPortal.class, Exchanges.COMMANDS, "rpa.post"),
    RPA_COMPLETED("RpaCompleted", RpaCompleted.class, Exchanges.EVENTS, "rpa.completed"),
    INGEST_CONTRACT("IngestContract", IngestContract.class, Exchanges.COMMANDS, "contract.ingest");

    private static final Map<String, MessageType> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(MessageType::typeName, Function.identity()));

    private static final Map<Class<? extends InvoiceMessage>, MessageType> BY_CLASS = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(MessageType::payloadClass, Function.identity()));

    private final String typeName;
    private final Class<? extends InvoiceMessage> payloadClass;
    private final String exchange;
    private final String routingKey;

    MessageType(String typeName, Class<? extends InvoiceMessage> payloadClass, String exchange, String routingKey) {
        this.typeName = typeName;
        this.payloadClass = payloadClass;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    /** AMQP {@code type} alanına yazılan ad, örn. {@code ExtractInvoice}. */
    public String typeName() {
        return typeName;
    }

    public Class<? extends InvoiceMessage> payloadClass() {
        return payloadClass;
    }

    public String exchange() {
        return exchange;
    }

    public String routingKey() {
        return routingKey;
    }

    /** Üreticinin yazdığı ve tüketicinin kabul ettiği şema sürümü. Tüm mesajlar v1'de 1'dir. */
    public int schemaVersion() {
        return 1;
    }

    public static Optional<MessageType> fromTypeName(String typeName) {
        return Optional.ofNullable(typeName).map(BY_NAME::get);
    }

    public static MessageType of(Class<? extends InvoiceMessage> payloadClass) {
        return BY_CLASS.get(payloadClass);
    }

    public static MessageType of(InvoiceMessage payload) {
        return of(payload.getClass());
    }
}
