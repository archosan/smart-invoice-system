package com.archosan.invoice.messaging.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Tek thread'de periyodik çalışan arka plan döngüsü (outbox relay, inbox temizliği).
 *
 * <p>{@code tick} {@code true} dönerse beklemeden yeni tura geçilir (iş birikmiş demektir), {@code false} dönerse
 * {@code interval} kadar beklenir. Turdaki istisna loglanır, döngü durmaz. {@link #stop()} süren turun bitmesini
 * {@code stopTimeout} kadar bekler, beklemeyi kesmek için thread'i interrupt etmez.
 */
public final class BackgroundLoop {

    private static final Logger log = LoggerFactory.getLogger(BackgroundLoop.class);

    private final String name;
    private final Duration interval;
    private final Duration stopTimeout;
    private final BooleanSupplier tick;

    private final Object monitor = new Object();
    private Thread worker;
    private CountDownLatch stopSignal;

    public BackgroundLoop(String name, Duration interval, Duration stopTimeout, BooleanSupplier tick) {
        this.name = name;
        this.interval = interval;
        this.stopTimeout = stopTimeout;
        this.tick = tick;
    }

    public void start() {
        synchronized (monitor) {
            if (worker != null) {
                return;
            }
            CountDownLatch signal = new CountDownLatch(1);
            stopSignal = signal;
            worker = Thread.ofPlatform().name(name).daemon(true).start(() -> run(signal));
        }
    }

    public void stop() {
        Thread running;
        synchronized (monitor) {
            running = worker;
            if (running == null) {
                return;
            }
            worker = null;
            stopSignal.countDown();
        }
        try {
            running.join(stopTimeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        synchronized (monitor) {
            return worker != null;
        }
    }

    private void run(CountDownLatch signal) {
        while (signal.getCount() > 0) {
            boolean again = false;
            try {
                again = tick.getAsBoolean();
            } catch (RuntimeException e) {
                log.error("{} turu başarısız", name, e);
            }
            if (again) {
                continue;
            }
            try {
                if (signal.await(interval.toNanos(), TimeUnit.NANOSECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
