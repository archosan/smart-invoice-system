package com.archosan.invoice.messaging;

import com.archosan.invoice.messaging.message.InvoiceMessage;

/** Broker'dan gelen ve çözümlenmiş bir mesaj. */
public record ReceivedMessage(MessageEnvelope envelope, InvoiceMessage payload) {
}
