package com.archosan.invoice.document.statemachine;

import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.message.InvoiceMessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Bir geçişi neyin tetiklediği; {@code status_transitions}'a yazılır.
 *
 * @param event   tetikleyen olay, örn. {@code ExtractionCompleted}, {@code DLQ:PostToPortal}, {@code UPLOAD}
 * @param actor   geçişi yapan, örn. {@code extraction-service}, {@code document-service}, {@code api}
 * @param reason    gerekçe (ret gibi insan kararlarında zorunlu, v2)
 * @param messageId tetikleyen mesaj; {@code status_transitions.message_id}
 * @param message   geç olay kaydı için mesaj bağlamı; verilirse uygulanamayan geçiş {@code LATE_EVENT} olarak yazılır.
 *                  API ve DLQ tetikleyicisinde {@code null} (DLQ mesajı zaten {@code dead_letters}'a park edilir)
 */
public record Trigger(String event, String actor, String reason, UUID messageId, MessageContext message) {

    public Trigger {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(actor, "actor");
    }

    /** Mesaj tetikleyicisi; olay adı mesaj tipidir. */
    public static Trigger message(String actor, MessageEnvelope envelope, InvoiceMessage payload, String sourceQueue) {
        return message(envelope.type().typeName(), actor, envelope, payload, sourceQueue);
    }

    /** Mesaj bağlamıyla, mesaj tipinden farklı bir olay adı. */
    public static Trigger message(String event, String actor, MessageEnvelope envelope, InvoiceMessage payload,
            String sourceQueue) {
        return new Trigger(event, actor, null, envelope.messageId(),
                new MessageContext(envelope, payload, sourceQueue));
    }

    /** DLQ tetikleyicisi; uygulanamazsa ayrıca {@code LATE_EVENT} yazılmaz, mesaj zaten park edilmiştir. */
    public static Trigger deadLetter(String event, String actor, UUID messageId) {
        return new Trigger(event, actor, null, messageId, null);
    }

    /** İnsan veya API tetikleyicisi (v2); geç kalırsa kayıt yazılmaz, çağıran 409 döner. */
    public static Trigger api(String event, String actor, String reason) {
        return new Trigger(event, actor, reason, null, null);
    }

    /** Aynı mesaj bağlamıyla, başka aktör ve gerekçe (örn. document-service'in aynı olay içindeki kararı). */
    public Trigger as(String otherActor, String otherReason) {
        return new Trigger(event, otherActor, otherReason, messageId, message);
    }

    /** @param sourceQueue mesajın geldiği kuyruk */
    public record MessageContext(MessageEnvelope envelope, InvoiceMessage payload, String sourceQueue) {

        public MessageContext {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(sourceQueue, "sourceQueue");
        }
    }
}
