package com.archosan.invoice.rpa.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Fatura başına dağıtık kilit (B-34, FR-R7): {@code lock:rpa:{supplierVkn}:{invoiceNo}}. Aynı faturanın iki örnekte
 * birden portala girilmesini önler (devirdeki örtüşme, ölü sanılan örnek, aynı faturanın iki belgesi). Kira olmadan
 * alınır, Redisson watchdog'u iş sürdükçe yeniler; süreç ölürse kilit kira dolunca düşer.
 *
 * <p><b>Fail-closed:</b> Redis'e ulaşılamazsa kilit alınamamış sayılır; çağıran portala dokunmaz. Kilit, kira bir
 * duraklamada (uzun GC) kaybedilirse tek başına yetmez; bunu portal ön araması (B-35) kapatır.
 *
 * <p>Redisson kilidi thread'e bağlıdır: alan thread bırakır (listener thread'i, eşzamanlılık 1).
 */
@Component
public class InvoiceLocks {

    private static final Logger log = LoggerFactory.getLogger(InvoiceLocks.class);

    private final RedissonClient redisson;

    public InvoiceLocks(RedissonClient redisson) {
        this.redisson = redisson;
    }

    static String key(String supplierVkn, String invoiceNo) {
        return "lock:rpa:" + supplierVkn + ":" + invoiceNo;
    }

    /** @return kilit alındıysa bırakılacak tutamak; başka sahipte ya da Redis'e ulaşılamıyorsa boş */
    public Optional<Held> tryAcquire(String supplierVkn, String invoiceNo) {
        RLock lock = redisson.getLock(key(supplierVkn, invoiceNo));
        try {
            if (lock.tryLock(0, TimeUnit.MILLISECONDS)) {
                return Optional.of(new Held(lock));
            }
            log.info("Fatura kilidi başka bir örnekte: {}", lock.getName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Redis'e ulaşılamadı, kilit alınamamış sayılıyor (fail-closed): {}", e.toString());
            return Optional.empty();
        }
    }

    /** Alınmış kilit; {@code close} bırakır. */
    public static final class Held implements AutoCloseable {

        private final RLock lock;

        private Held(RLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException e) {
                // Redis'e bırakırken ulaşılamazsa kilit kira dolunca kendiliğinden düşer.
                log.warn("Fatura kilidi bırakılamadı, kira dolunca düşecek: {} ({})", lock.getName(), e.toString());
            }
        }
    }
}
