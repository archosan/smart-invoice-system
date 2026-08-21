package com.archosan.invoice.llm;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * Semaforun Redis istemcisi (B-18 K5: Boot starter yerine çekirdek paket). Tembel başlatılır: Redis kapalıyken servis
 * yine açılır, semafor yerel sınıra düşer. Servis bean'i {@code spring.data.redis.*} değerleriyle kurar.
 */
public final class LlmRedisson {

    private LlmRedisson() {
    }

    public static RedissonClient create(String host, int port) {
        Config config = new Config();
        config.setLazyInitialization(true);
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectTimeout(2000)
                .setRetryAttempts(1)
                .setTimeout(3000);
        return Redisson.create(config);
    }
}
