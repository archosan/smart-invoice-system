package com.archosan.invoice.messaging.consumer;

/**
 * Uzun işin sonucunu yazar. Handler ikisinden birini tam bir kez çağırır: {@link #complete} (iş bitti) ya da
 * {@link #defer} (iş şimdi yapılamaz, mesaj aynı kimlikle sonra gelecek).
 */
public interface InboxCompletion {

    /**
     * Bir transaction açar, mesajı inbox'a kaydeder ve kayıt yeniyse {@code work}'ü aynı transaction'da çalıştırır
     * (sonuç tabloları, {@code OutboxWriter.add}). Mesaj bu arada başka bir kopyada işlendiyse {@code work}
     * çalıştırılmaz ve sonuç atılır. Bir kez çağrılır.
     *
     * @return sonuç yazıldıysa {@code true}; mesaj başka bir kopyada işlendiği için atıldıysa {@code false}
     */
    boolean complete(Runnable work);

    /**
     * İşi ertele (B-34): {@code work}'ü kısa bir transaction'da çalıştırır ama mesajı inbox'a <b>kaydetmez</b>;
     * ardından mesaj ack edilir. {@code work} mesajı aynı kimlikle bir bekleme odasına yeniden yayınlar
     * ({@code OutboxWriter.republish}); mesaj geri döndüğünde inbox onu işlenmemiş bulur ve handler yeniden çağrılır.
     * Teslim limitini tüketmez.
     */
    void defer(Runnable work);
}
