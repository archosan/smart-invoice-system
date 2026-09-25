package com.archosan.invoice.rpa;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RpaServiceApplicationTests extends RpaIntegrationTest {

    @Autowired
    private SimpleMessageListenerContainer postToPortalContainer;
    @Autowired
    private RpaProperties properties;
    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    /**
     * B-27: kapanışta sürmekte olan giriş sonuna kadar beklenir; listener girişin üst sınırı kadar bekler, Spring'in
     * kapanış aşaması ondan uzundur (yoksa DB giriş bitmeden kapanırdı). Compose'daki stop_grace_period (150 sn) de
     * kapanış aşamasından (2 dk) uzundur.
     */
    @Test
    void shutdownWaitsForTheLongestPossibleEntry() {
        Duration maxProcessing = properties.portal().maxProcessingTime();

        assertThat(ReflectionTestUtils.getField(postToPortalContainer, "shutdownTimeout"))
                .isEqualTo(maxProcessing.toMillis());
        assertThat(environment.getRequiredProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class))
                .isGreaterThan(maxProcessing);
    }
}
