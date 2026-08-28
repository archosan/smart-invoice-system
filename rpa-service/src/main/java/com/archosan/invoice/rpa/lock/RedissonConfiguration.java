package com.archosan.invoice.rpa.lock;

import com.archosan.invoice.rpa.RpaProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link RedissonClient}, {@code spring.data.redis.*} ayarlarından (extraction-service ile aynı kalıp). Tembel
 * başlatılır: Redis kapalıyken servis yine açılır, kilit alınamaz sayılır (fail-closed, B-34). Kısa zaman aşımları
 * Redis'e ulaşılamadığını çabuk anlatır; bekleyen fatura portala dokunmadan bekleme odasına gider.
 *
 * <p>Kilit watchdog'u kiranın ({@code invoice.rpa.lock-lease}) üçte birinde yeniler.
 */
@Configuration(proxyBeanMethods = false)
class RedissonConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(@Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port, RpaProperties properties) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.setLockWatchdogTimeout(properties.lockLease().toMillis());
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectTimeout(2000)
                .setRetryAttempts(1)
                .setTimeout(3000);
        return Redisson.create(config);
    }
}
