package com.archosan.invoice.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Konteynerdeki ayarla ({@code LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs}, compose x-app-env) log satırının JSON olduğunu
 * ve tek bir faturayı servisler arasında bulmaya yetecek alanları taşıdığını doğrular (NFR-06).
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredLoggingTest.class);

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    @AfterEach
    void restorePlainLogging() {
        initializeLogging(new MockEnvironment());
    }

    @Test
    void ecsLogLineCarriesCorrelationAndServiceName(CapturedOutput output) {
        initializeLogging(new MockEnvironment()
                .withProperty("logging.structured.format.console", "ecs")
                .withProperty("spring.application.name", "document-service"));
        UUID documentId = UUID.randomUUID();

        try (CorrelationScope ignored = CorrelationScope.forMessage(documentId.toString(), "msg-1", "ExtractInvoice")) {
            log.info("yapısal log denemesi");
        }

        String line = Arrays.stream(output.getOut().split("\\R"))
                .filter(l -> l.contains("yapısal log denemesi"))
                .findFirst()
                .orElseThrow();
        JsonNode json = JsonMapper.builder().build().readTree(line);
        assertThat(json.path("message").asString()).isEqualTo("yapısal log denemesi");
        assertThat(json.path("correlationId").asString()).isEqualTo(documentId.toString());
        assertThat(json.path("messageId").asString()).isEqualTo("msg-1");
        assertThat(json.path("messageType").asString()).isEqualTo("ExtractInvoice");
        assertThat(json.at("/service/name").asString()).isEqualTo("document-service");
    }

    private void initializeLogging(MockEnvironment environment) {
        loggingSystem.cleanUp();
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), null, null);
    }
}
