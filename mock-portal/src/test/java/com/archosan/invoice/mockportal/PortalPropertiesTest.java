package com.archosan.invoice.mockportal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortalPropertiesTest {

    @Test
    void blankPasswordFailsBindingWithoutEchoingValues() {
        // .env'de boş PORTAL_PASSWORD: yml'daki ${PORTAL_PASSWORD:} boş gelir, portal açılmamalı.
        assertThatThrownBy(() -> bind(Map.of("mock-portal.username", "rpa-bot", "mock-portal.password", " ")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("PORTAL_PASSWORD");
    }

    @Test
    void blankPatternMeansNoFailureInjection() {
        PortalProperties properties = bind(Map.of("mock-portal.username", "u", "mock-portal.password", "p",
                "mock-portal.fail-invoice-no-pattern", ""));

        assertThat(properties.failPattern()).isEmpty();
        assertThat(properties.pageSize()).isEqualTo(10);
    }

    @Test
    void passwordIsNotInToString() {
        PortalProperties properties = new PortalProperties("u", "gizli-deger", 10, null, null, Duration.ofSeconds(1),
                Duration.ofMinutes(30), 0, null, Duration.ZERO);

        assertThat(properties.toString()).doesNotContain("gizli-deger");
    }

    private static PortalProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bind("mock-portal", PortalProperties.class).get();
    }
}
