package com.archosan.invoice.llm;

import com.archosan.invoice.testsupport.InfrastructureContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gerçek Redis (test-support); her test kendi semafor adını kullanmasın diye Redis temizlenir. */
class LlmSemaphoreTest {

    private RedissonClient redisson;

    @BeforeEach
    void connect() {
        Map<String, Supplier<Object>> props = new HashMap<>();
        InfrastructureContainers.registerRedis(props::put);
        redisson = client(props.get("spring.data.redis.host").get() + ":" + props.get("spring.data.redis.port").get());
        redisson.getKeys().flushall();
    }

    @AfterEach
    void close() {
        redisson.shutdown();
    }

    @Test
    void allowsAtMostConfiguredConcurrency() throws Exception {
        LlmSemaphore semaphore = new LlmSemaphore(redisson, properties(2, Duration.ofSeconds(10)));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        List<CompletableFuture<Void>> calls = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            calls.add(CompletableFuture.runAsync(() -> semaphore.withPermit(() -> {
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                sleep(150);
                active.decrementAndGet();
                return null;
            })));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);

        assertThat(maxActive).hasValue(2);
        assertThat(redisson.getPermitExpirableSemaphore(LlmSemaphore.NAME).availablePermits()).isEqualTo(2);
    }

    @Test
    void permitIsReleasedEvenWhenWorkFails() {
        LlmSemaphore semaphore = new LlmSemaphore(redisson, properties(1, Duration.ofSeconds(1)));

        assertThatThrownBy(() -> semaphore.withPermit(() -> {
            throw new IllegalStateException("LLM hatası");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(semaphore.withPermit(() -> "ikinci çağrı")).isEqualTo("ikinci çağrı");
    }

    @Test
    void waitingLongerThanPermitWaitFails() throws Exception {
        LlmSemaphore semaphore = new LlmSemaphore(redisson, properties(1, Duration.ofMillis(300)));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Object> holder = CompletableFuture.supplyAsync(() -> semaphore.withPermit(() -> {
            holding.countDown();
            await(release);
            return null;
        }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> semaphore.withPermit(() -> "bekleyen"))
                .isInstanceOf(PermitUnavailableException.class);
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
    }

    @Test
    void fallsBackToLocalSemaphoreWhenRedisIsUnreachable() throws Exception {
        RedissonClient unreachable = client("127.0.0.1:1");
        try {
            LlmSemaphore semaphore = new LlmSemaphore(unreachable, properties(2, Duration.ofSeconds(10)));
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            List<CompletableFuture<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(CompletableFuture.runAsync(() -> semaphore.withPermit(() -> {
                    maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    sleep(100);
                    active.decrementAndGet();
                    return null;
                })));
            }
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);

            // Yerel izin sayısı 1: Redis yokken tek başına karar veren servis temkinli davranır.
            assertThat(maxActive).hasValue(1);
        } finally {
            unreachable.shutdown();
        }
    }

    /** B-48: izin bekleme ve çağrı süresi mod etiketiyle, alınamayan izin sayılır. */
    @Test
    void recordsWaitCallAndUnavailablePermits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmSemaphore semaphore = new LlmSemaphore(redisson, properties(1, Duration.ofMillis(200)), registry);

        semaphore.withPermit(() -> "tamam");
        assertThat(registry.get(LlmSemaphore.CALLS).tag("mode", "redis").timer().count()).isEqualTo(1);
        assertThat(registry.get(LlmSemaphore.PERMIT_WAIT).tag("mode", "redis").timer().count()).isEqualTo(1);

        assertThatThrownBy(() -> semaphore.withPermit(() -> semaphore.withPermit(() -> "iç içe")))
                .isInstanceOf(PermitUnavailableException.class);
        assertThat(registry.get("invoice.llm.permit.unavailable").tag("mode", "redis").counter().count())
                .isEqualTo(1);
    }

    /** Kira 60 sn, yerel izin 1. */
    private static LlmSemaphore.Settings properties(int permits, Duration permitWait) {
        return new LlmSemaphore.Settings(permits, Duration.ofSeconds(60), permitWait, 1);
    }

    private static RedissonClient client(String address) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.useSingleServer().setAddress("redis://" + address).setConnectTimeout(500).setRetryAttempts(0)
                .setTimeout(1000);
        return Redisson.create(config);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
