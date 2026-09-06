package com.archosan.invoice.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationScopeTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsCorrelationIdAndRemovesItOnClose() {
        UUID documentId = UUID.randomUUID();

        try (CorrelationScope ignored = CorrelationScope.open(documentId)) {
            assertThat(MDC.get("correlationId")).isEqualTo(documentId.toString());
        }

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void nestedScopeRestoresOuterValues() {
        try (CorrelationScope outer = CorrelationScope.forMessage("doc-1", "msg-1", "ExtractInvoice")) {
            try (CorrelationScope inner = CorrelationScope.forMessage("doc-2", null, null)) {
                assertThat(MDC.get("correlationId")).isEqualTo("doc-2");
                assertThat(MDC.get("messageId")).isEqualTo("msg-1");
                assertThat(MDC.get("messageType")).isEqualTo("ExtractInvoice");
            }
            assertThat(MDC.get("correlationId")).isEqualTo("doc-1");
        }

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void leavesUnrelatedMdcKeysAlone() {
        MDC.put("traceId", "abc");

        try (CorrelationScope ignored = CorrelationScope.open(UUID.randomUUID())) {
            assertThat(MDC.get("traceId")).isEqualTo("abc");
        }

        assertThat(MDC.get("traceId")).isEqualTo("abc");
    }
}
