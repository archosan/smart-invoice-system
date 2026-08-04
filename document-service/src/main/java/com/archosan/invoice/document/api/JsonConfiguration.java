package com.archosan.invoice.document.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

import java.math.BigDecimal;

/**
 * API JSON'unda {@link BigDecimal} string olarak, bilimsel gösterimsiz yazılır; mesajlarla ve {@code invoice_data}
 * ile aynı kural (CLAUDE.md: tutarlar JSON'da string).
 */
@Configuration(proxyBeanMethods = false)
class JsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer bigDecimalAsString() {
        return builder -> builder
                .withConfigOverride(BigDecimal.class,
                        o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
