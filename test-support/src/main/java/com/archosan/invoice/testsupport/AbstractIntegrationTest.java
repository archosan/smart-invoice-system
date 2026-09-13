package com.archosan.invoice.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Entegrasyon testlerinin taban sınıfı: Spring context'i RabbitMQ konteynerine bağlı açar. Her servis kendi
 * veritabanını tek bir {@link DynamicPropertySource} metoduyla seçer:
 *
 * <pre>{@code
 * abstract class DocumentIntegrationTest extends AbstractIntegrationTest {
 *
 *     @DynamicPropertySource
 *     static void database(DynamicPropertyRegistry registry) {
 *         InfrastructureContainers.registerPostgres(registry, ServiceDatabase.DOCUMENT);
 *     }
 * }
 * }</pre>
 *
 * Redis'e ihtiyaç duyan servis aynı şekilde {@link InfrastructureContainers#registerRedis} ekler.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void rabbitMq(DynamicPropertyRegistry registry) {
        InfrastructureContainers.registerRabbitMq(registry);
    }
}
