package com.archosan.invoice.messaging;

import com.archosan.invoice.testsupport.AbstractIntegrationTest;
import com.archosan.invoice.testsupport.InfrastructureContainers;
import com.archosan.invoice.testsupport.ServiceDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** common-messaging'in entegrasyon testleri document_db'yi, document_user ile kullanır. */
public abstract class MessagingIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        InfrastructureContainers.registerPostgres(registry, ServiceDatabase.DOCUMENT);
    }
}
