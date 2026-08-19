package com.archosan.invoice.extraction.semaphore;

import com.archosan.invoice.extraction.ExtractionProperties;
import com.archosan.invoice.llm.LlmRedisson;
import com.archosan.invoice.llm.LlmSemaphore;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM semaforu (ADR-07, {@code llm-support}): Redis istemcisi {@code spring.data.redis.*}'tan, sınırlar
 * {@code invoice.extraction.llm.*}'dan.
 */
@Configuration(proxyBeanMethods = false)
class RedissonConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(@Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return LlmRedisson.create(host, port);
    }

    /** Kira LLM zaman aşımının iki katı: izni tutarken çöken servisin izni bu sürede geri gelir. */
    @Bean
    LlmSemaphore llmSemaphore(RedissonClient redisson, ExtractionProperties properties, MeterRegistry registry) {
        ExtractionProperties.Llm llm = properties.llm();
        return new LlmSemaphore(redisson, new LlmSemaphore.Settings(llm.permits(), llm.timeout().multipliedBy(2),
                llm.permitWait(), llm.localPermits()), registry);
    }
}
