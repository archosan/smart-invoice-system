package com.archosan.invoice.rpa.portal;

import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** B-37: oturum düşünce yeniden login (FR-R4) ve başarısız denemenin ekran görüntüsü (FR-R6). Gerçek Chromium. */
@ExtendWith(OutputCaptureExtension.class)
class PortalSessionAndScreenshotTest {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};

    /**
     * Login'den hemen sonra tarayıcının cookie'leri silinir: portal için sunucu tarafında düşmüş oturumla aynıdır,
     * sonraki istek login'e yönlenir. Bot bir kez yeniden login olur, akışı ön aramadan baştan yapar ve faturayı bir
     * kez girer.
     */
    @Test
    void droppedSessionIsDetectedAndBotLogsInAgain(CapturedOutput output) {
        String invoiceNo = "SESS-" + UUID.randomUUID();

        PlaywrightPortal.Outcome outcome = new PlaywrightPortal(properties(Map.of()))
                .submit(invoice(invoiceNo), page -> page.context().clearCookies());

        assertThat(outcome).isInstanceOf(PlaywrightPortal.Entered.class);
        assertThat(MockPortal.invoices(invoiceNo)).singleElement()
                .satisfies(entered -> assertThat(entered.refNo()).isEqualTo(outcome.portalRefNo()));
        assertThat(output.getAll()).contains("Portal oturumu düştü", "yeniden login");
    }

    @Test
    void failedAttemptCarriesStepAndPngScreenshot() {
        PortalAttemptFailedException failure = catchThrowableOfType(PortalAttemptFailedException.class,
                () -> new PlaywrightPortal(properties(Map.of(
                        "invoice.rpa.portal.selectors.invoice-submit", "#yok",
                        "invoice.rpa.portal.timeout", "2s")))
                        .submit(invoice("SHOT-" + UUID.randomUUID())));

        assertThat(failure.step()).isEqualTo("gönderme");
        assertThat(failure.screenshot()).isNotNull();
        assertThat(Arrays.copyOf(failure.screenshot(), 4)).isEqualTo(PNG_SIGNATURE);
    }

    private static RpaProperties properties(Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>(Map.of(
                "invoice.rpa.portal.url", MockPortal.url(),
                "invoice.rpa.portal.username", MockPortal.USERNAME,
                "invoice.rpa.portal.password", MockPortal.PASSWORD));
        values.putAll(overrides);
        return new Binder(new MapConfigurationPropertySource(values)).bind("invoice.rpa", RpaProperties.class).get();
    }

    private static PortalValues invoice(String invoiceNo) {
        return PortalValues.of(new PostToPortal(UUID.randomUUID(), "4810293756", "Anadolu Rulman A.Ş.", invoiceNo,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), new BigDecimal("5100.00"),
                new BigDecimal("850.00"), "TRY"));
    }
}
