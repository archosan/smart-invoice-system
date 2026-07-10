package com.archosan.invoice.messaging.consumer;

import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.message.InvoiceMessage;

/**
 * Uzun süren işin (LLM, RPA) handler'ı. İş transaction dışında yapılır; sonuç {@link InboxCompletion#complete} ile
 * kısa bir transaction'da yazılır (DECISIONS.md §5, "uzun işler").
 *
 * <p>Handler her yolda {@code complete}'i ya da {@code defer}'i (iş şimdi yapılamaz, mesaj aynı kimlikle sonra gelecek)
 * bir kez çağırmalıdır; çağırmadan dönmek programlama hatası sayılır, mesaj
 * kuyruğa geri konur ve teslim limitinden sonra DLQ'ya düşer. İstisnaların anlamı {@link MessageHandler} ile
 * aynıdır.
 */
@FunctionalInterface
public interface LongRunningMessageHandler<T extends InvoiceMessage> {

    void handle(MessageEnvelope envelope, T message, InboxCompletion completion);
}
