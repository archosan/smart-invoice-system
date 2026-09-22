package com.archosan.invoice.mockportal.fault;

import com.archosan.invoice.mockportal.PortalProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultInjectorTest {

    @Test
    void sameSeedGivesSameSequence() {
        assertThat(sequence(0.3, 42L)).isEqualTo(sequence(0.3, 42L)).contains(true, false);
    }

    @Test
    void rateZeroNeverFailsAndRateOneAlwaysFails() {
        assertThat(sequence(0, null)).containsOnly(false);
        assertThat(sequence(1, null)).containsOnly(true);
    }

    @Test
    void rateOutsideZeroToOneIsRejected() {
        assertThatThrownBy(() -> properties(1.5, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<Boolean> sequence(double rate, Long seed) {
        FaultInjector injector = new FaultInjector(properties(rate, seed));
        List<Boolean> result = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            result.add(injector.shouldFail());
        }
        return result;
    }

    private static PortalProperties properties(double rate, Long seed) {
        return new PortalProperties("u", "p", 10, null, null, Duration.ofSeconds(1), Duration.ofMinutes(30), rate,
                seed, Duration.ZERO);
    }
}
