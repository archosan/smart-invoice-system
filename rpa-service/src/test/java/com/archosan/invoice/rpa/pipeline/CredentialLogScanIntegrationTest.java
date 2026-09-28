package com.archosan.invoice.rpa.pipeline;

import com.archosan.invoice.messaging.InvoiceMessageConverter;
import com.archosan.invoice.messaging.MessageEnvelope;
import com.archosan.invoice.messaging.MessageType;
import com.archosan.invoice.messaging.message.PostToPortal;
import com.archosan.invoice.rpa.MockPortal;
import com.archosan.invoice.rpa.RpaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-10 log taraması (B-26): giriş adımı şifre doldurulduktan sonra her denemede düşer; dinleyici her hatayı yığın
 * iziyle loglar, mesaj teslim limitinden sonra DLQ'ya gider. Bütün konsol çıktısında şifre geçmemeli.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "invoice.rpa.portal.selectors.login-submit=#yok",
        "invoice.rpa.portal.timeout=2s",
        // Bekleme odası turları olmadan doğrudan DLQ (B-37); tarama tek denemenin çıktısında.
        "invoice.rpa.max-attempts=1"})
class CredentialLogScanIntegrationTest extends RpaIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private InvoiceMessageConverter converter;
    @Autowired
    private AmqpAdmin amqpAdmin;

    @Test
    void failingLoginsAreLoggedWithoutPassword(CapturedOutput output) {
        amqpAdmin.purgeQueue("rpa.post-to-portal.dlq", false);
        UUID documentId = UUID.randomUUID();
        PostToPortal command = new PostToPortal(documentId, "4810293756", "Anadolu Rulman A.Ş.",
                "LOG-" + UUID.randomUUID(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                new BigDecimal("5100.00"), new BigDecimal("850.00"), "TRY");

        rabbitTemplate.send("invoice.commands", "rpa.post", converter.toAmqpMessage(
                MessageEnvelope.of(UUID.randomUUID(), documentId, MessageType.POST_TO_PORTAL), command));

        Message deadLettered = rabbitTemplate.receive("rpa.post-to-portal.dlq", 60_000);
        assertThat(deadLettered).isNotNull();
        // Playwright'ın call log'u loglandı (tarama boşa geçmiyor), şifre loglanmadı.
        assertThat(output.getAll()).contains("#yok").doesNotContain(MockPortal.PASSWORD);
    }
}
