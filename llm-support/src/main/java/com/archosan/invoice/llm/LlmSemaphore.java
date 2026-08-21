package com.archosan.invoice.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * LLM eşzamanlılık sınırı (NFR-08, ADR-07): servisler arası Redis {@code sem:llm} (Redisson
 * {@link RPermitExpirableSemaphore}). İzin kiralıdır: servis izni tutarken çökerse izin kira (LLM zaman aşımının iki
 * katı) dolunca geri gelir.
 *
 * <p>Redis'e ulaşılamazsa yerel semafora düşülür ({@code localPermits}, önerilen 1: diğer servis aynı GPU'yu
 * kullandığı için tek başına karar verirken temkinli). İzin {@code permitWait} içinde alınamazsa
 * {@link PermitUnavailableException}; mesaj geri konur.
 *
 * <p>LLM çağıran her servis (extraction, compliance) aynı adı ve aynı izin sayısını kullanır (B-46'da ortak modüle
 * taşındı). Bean'i servis kendi ayarlarından kurar ({@link Settings}).
 *
 * <p>Metrikler (B-48): {@code invoice.llm.permit.wait} (izin bekleme), {@code invoice.llm.calls} (izinle yapılan iş),
 * ikisi de {@code mode} = {@code redis} | {@code local} etiketli; {@code invoice.llm.permit.unavailable} (süresinde
 * alınamayan izin). Kayıt defteri verilmezse {@link Metrics#globalRegistry} (boşsa no-op).
 */
public class LlmSemaphore {

    /**
     * @param permits      servisler arası toplam eşzamanlı çağrı (ilk kuran örnek belirler)
     * @param lease        izin kirası; tutan servis çökerse bu sürede geri gelir (LLM zaman aşımının iki katı önerilir)
     * @param permitWait   izin için en uzun bekleme
     * @param localPermits Redis yokken bu örneğin yerel sınırı
     */
    public record Settings(int permits, Duration lease, Duration permitWait, int localPermits) {
    }

    static final String NAME = "sem:llm";
    static final String PERMIT_WAIT = "invoice.llm.permit.wait";
    static final String CALLS = "invoice.llm.calls";
    private static final String REDIS = "redis";
    private static final String LOCAL = "local";

    private static final Logger log = LoggerFactory.getLogger(LlmSemaphore.class);

    private final RedissonClient redisson;
    private final int permits;
    private final Duration lease;
    private final Duration wait;
    private final Semaphore local;
    private volatile boolean initialized;

    private final MeterRegistry registry;

    public LlmSemaphore(RedissonClient redisson, Settings settings) {
        this(redisson, settings, Metrics.globalRegistry);
    }

    public LlmSemaphore(RedissonClient redisson, Settings settings, MeterRegistry registry) {
        this.registry = registry;
        this.redisson = redisson;
        this.permits = settings.permits();
        this.lease = settings.lease();
        this.wait = settings.permitWait();
        this.local = new Semaphore(settings.localPermits(), true);
    }

    public <T> T withPermit(Supplier<T> work) {
        String permitId;
        RPermitExpirableSemaphore semaphore;
        long waitStart = System.nanoTime();
        try {
            semaphore = redisSemaphore();
            permitId = semaphore.tryAcquire(wait.toMillis(), lease.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PermitUnavailableException("İzin beklenirken kesildi", e);
        } catch (RuntimeException redisDown) {
            log.warn("Redis'e ulaşılamadı, yerel LLM semaforuna düşülüyor: {}", redisDown.getMessage());
            return withLocalPermit(work);
        }
        if (permitId == null) {
            unavailable(REDIS);
            throw new PermitUnavailableException("sem:llm izni " + wait + " içinde alınamadı", null);
        }
        record(PERMIT_WAIT, REDIS, System.nanoTime() - waitStart);
        long callStart = System.nanoTime();
        try {
            return work.get();
        } finally {
            record(CALLS, REDIS, System.nanoTime() - callStart);
            release(semaphore, permitId);
        }
    }

    private RPermitExpirableSemaphore redisSemaphore() {
        RPermitExpirableSemaphore semaphore = redisson.getPermitExpirableSemaphore(NAME);
        if (!initialized) {
            // Yalnızca ilk kez ayarlar; başka bir örnek ayarladıysa dokunmaz.
            semaphore.trySetPermits(permits);
            initialized = true;
        }
        return semaphore;
    }

    private void release(RPermitExpirableSemaphore semaphore, String permitId) {
        try {
            semaphore.release(permitId);
        } catch (RuntimeException e) {
            log.warn("sem:llm izni bırakılamadı, kira dolunca geri gelecek: {}", e.getMessage());
        }
    }

    private <T> T withLocalPermit(Supplier<T> work) {
        long waitStart = System.nanoTime();
        try {
            if (!local.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                unavailable(LOCAL);
                throw new PermitUnavailableException("Yerel LLM izni " + wait + " içinde alınamadı", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PermitUnavailableException("İzin beklenirken kesildi", e);
        }
        record(PERMIT_WAIT, LOCAL, System.nanoTime() - waitStart);
        long callStart = System.nanoTime();
        try {
            return work.get();
        } finally {
            record(CALLS, LOCAL, System.nanoTime() - callStart);
            local.release();
        }
    }

    private void record(String name, String mode, long nanos) {
        Timer.builder(name).tag("mode", mode).register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }

    private void unavailable(String mode) {
        Counter.builder("invoice.llm.permit.unavailable").tag("mode", mode).register(registry).increment();
    }
}
