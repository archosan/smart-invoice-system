package com.archosan.invoice.extraction;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * application.yml'daki {@code invoice.extraction.validation} anahtarları gerçekten bağlanıyor mu: yanlış yazılmış bir
 * anahtar Spring tarafından sessizce yok sayılır ve varsayılan kullanılırdı (B-29'da ağırlıklar değişince fark
 * edilmezdi). Her değer varsayılandan farklı bir sayıyla değiştirilip bağlanır.
 */
class ExtractionPropertiesBindingTest {

    @Test
    void everyValidationKeyInApplicationYmlBinds() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        Map<String, Object> properties = new HashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> yml = (Map<String, Object>) sources.getFirst().getSource();
        yml.forEach((key, value) -> {
            if (key.startsWith("invoice.extraction.validation.")) {
                properties.put(key, "0.77");
            }
        });
        properties.put("invoice.extraction.storage-dir", "/x");

        ExtractionProperties bound = new Binder(new MapConfigurationPropertySource(properties))
                .bind("invoice.extraction", ExtractionProperties.class).get();

        assertThat(properties).hasSizeGreaterThan(10);
        ExtractionProperties.Validation v = bound.validation();
        ExtractionProperties.Weights w = v.weights();
        assertThat(List.of(v.perLineTolerance(), v.minimumTolerance(), v.totalTolerance(), w.requiredFieldsPresent(),
                w.vkn10Digits(), w.linesSumEqualsSubtotal(), w.subtotalPlusVatEqualsTotal(),
                w.invoiceDateNotAfterDueDate(), w.amountsParsed(), w.vatMatchesLines()))
                .allSatisfy(value -> assertThat(value).isEqualByComparingTo(new BigDecimal("0.77")));
    }

    @Test
    void blankModelFailsBinding() {
        // .env'de boş OLLAMA_CHAT_MODEL: yml'daki ${OLLAMA_CHAT_MODEL:…} varsayılanı devreye girmez, değer "" gelir.
        Map<String, Object> properties = Map.of("invoice.extraction.llm.model", " ");

        assertThatThrownBy(() -> new Binder(new MapConfigurationPropertySource(properties))
                .bind("invoice.extraction", ExtractionProperties.class))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("OLLAMA_CHAT_MODEL");
    }
}
