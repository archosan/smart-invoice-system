package com.archosan.invoice.rpa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * application.yml'daki seçici ve yol anahtarları gerçekten bağlanıyor mu: yanlış yazılmış bir anahtar sessizce yok
 * sayılır ve koddaki varsayılan kullanılırdı; portal değişince yml'ı düzeltmek etkisiz kalırdı (NFR-09).
 */
class RpaPropertiesBindingTest {

    @Test
    void everySelectorAndPathKeyInApplicationYmlBinds() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Object> yml = (Map<String, Object>) sources.getFirst().getSource();
        Map<String, Object> properties = new HashMap<>(credentials());
        yml.forEach((key, value) -> {
            if (key.startsWith("invoice.rpa.portal.selectors.") || key.startsWith("invoice.rpa.portal.paths.")) {
                properties.put(key, "#x");
            }
        });

        RpaProperties.Portal portal = bind(properties).portal();

        assertThat(properties).hasSizeGreaterThan(30);
        RpaProperties.Selectors s = portal.selectors();
        assertThat(List.of(s.username(), s.password(), s.loginSubmit(), s.loginError(), s.loggedIn(),
                s.invoiceForm(), s.supplierVkn(), s.supplierName(), s.invoiceNo(), s.invoiceDate(), s.dueDate(),
                s.grandTotal(), s.vatTotal(), s.currency(), s.invoiceSubmit(), s.formErrors(), s.fieldError(),
                s.refNo(), s.portalError(), s.searchInvoiceNo(), s.searchSubmit(), s.resultTable(), s.resultRow(),
                s.rowInvoiceNo(), s.rowSupplierVkn(), s.rowRefNoAttribute(), s.nextPage(), portal.paths().login(),
                portal.paths().newInvoice(), portal.paths().search()))
                .allSatisfy(value -> assertThat(value).isEqualTo("#x"));
    }

    @Test
    void blankPasswordFailsBinding() {
        Map<String, Object> properties = new HashMap<>(credentials());
        properties.put("invoice.rpa.portal.password", "");

        assertThatThrownBy(() -> bind(properties))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("PORTAL_PASSWORD");
    }

    @Test
    void passwordIsNotInToStringAndUrlJoinsPaths() {
        Map<String, Object> properties = new HashMap<>(credentials());
        properties.put("invoice.rpa.portal.url", "http://portal:8080/");

        RpaProperties.Portal portal = bind(properties).portal();

        assertThat(portal.toString()).doesNotContain("gizli-deger");
        assertThat(portal.resolve("/login")).isEqualTo("http://portal:8080/login");
    }

    private static Map<String, Object> credentials() {
        return Map.of("invoice.rpa.portal.username", "rpa-bot", "invoice.rpa.portal.password", "gizli-deger");
    }

    private static RpaProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bind("invoice.rpa", RpaProperties.class)
                .get();
    }
}
