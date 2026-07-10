package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.message.InvoiceMessage;

/**
 * Kısa bir işin handler'ı. Dinleyici onu bir transaction içinde, mesajı inbox'a kaydettikten sonra çağırır; handler
 * iş verisini ve outbox'ı aynı transaction'da yazar. Mesaj daha önce işlendiyse handler çağrılmaz.
 *
 * <p>Normal dönüş commit ve ack demektir. {@link org.springframework.amqp.AmqpRejectAndDontRequeueException} kalıcı
 * hata demektir; transaction geri alınır, mesaj tekrar denenmeden DLQ'ya gider. Diğer her istisna transaction'ı geri
 * alır, mesajı kuyruğa geri koyar ve broker'ın teslim sayacını artırır; sayaç teslim limitine ulaşınca mesaj DLQ'ya
 * gider.
 */
@FunctionalInterface
public interface MessageHandler<T extends InvoiceMessage> {

    void handle(MessageEnvelope envelope, T message);
}
