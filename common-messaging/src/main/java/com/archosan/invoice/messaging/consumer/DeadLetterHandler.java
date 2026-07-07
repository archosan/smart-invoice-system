package com.archosan.invoice.messaging.consumer;

/**
 * DLQ mesajını işler (park, durum geçişi). Inbox kaydıyla aynı transaction'da çağrılır; normal dönüş ack demektir,
 * her istisna mesajı DLQ'ya geri koyar.
 */
@FunctionalInterface
public interface DeadLetterHandler {

    void handle(DeadLetter deadLetter);
}
