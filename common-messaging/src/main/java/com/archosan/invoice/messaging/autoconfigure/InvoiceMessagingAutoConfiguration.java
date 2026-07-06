package com.archosan.invoice.messaging.autoconfigure;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.support.MessagingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Mesaj dönüştürücüsü ve metrikler; hem outbox hem dinleyiciler kullanır. */
@AutoConfiguration
public class InvoiceMessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    InvoiceMessageConverter invoiceMessageConverter() {
        return new InvoiceMessageConverter();
    }

    /** B-48: kayıt defteri varsa (Actuator) metrikler ona yazılır, yoksa no-op. */
    @Bean
    @ConditionalOnMissingBean
    MessagingMetrics messagingMetrics(ObjectProvider<MeterRegistry> registry) {
        return new MessagingMetrics(registry.getIfAvailable());
    }
}
