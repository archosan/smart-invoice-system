package com.archosan.invoice.document.settings;

import com.archosan.invoice.document.DocumentIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsTest extends DocumentIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void restoreThreshold() {
        setThreshold("0.80");
    }

    @Test
    void cachesValuesUntilTtlExpires() {
        MutableClock clock = new MutableClock();
        Settings settings = new Settings(jdbc, Duration.ofSeconds(5), clock);
        assertThat(settings.confidenceThreshold()).isEqualByComparingTo("0.80");

        setThreshold("0.90");
        clock.advance(Duration.ofSeconds(4));
        assertThat(settings.confidenceThreshold()).isEqualByComparingTo("0.80");

        clock.advance(Duration.ofSeconds(1));
        assertThat(settings.confidenceThreshold()).isEqualByComparingTo("0.90");
    }

    private void setThreshold(String value) {
        jdbc.sql("UPDATE settings SET value = :value WHERE key = 'confidence_threshold'").param("value", value).update();
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
