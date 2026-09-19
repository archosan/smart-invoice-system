package com.archosan.invoice.document.maintenance;

import com.archosan.invoice.document.DocumentIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** B-48, NFR-06: /actuator/prometheus yalnız ADMIN; takılı kayıt, açık park ve outbox göstergeleri yayınlanır. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "invoice.messaging.outbox.relay.enabled=false")
class MetricsEndpointIntegrationTest extends DocumentIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StuckDocumentDetector detector;

    @Test
    void exposesBusinessGaugesToAdminOnly() {
        UUID stuck = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status, updated_at)
                        VALUES (:id, :sha, 'file:///x.pdf', 'RECEIVED', now() - interval '2 hours')
                        """)
                .param("id", stuck).param("sha", (stuck.toString() + stuck).replace("-", "").substring(0, 64)).update();
        jdbc.sql("""
                        INSERT INTO dead_letters (id, kind, source_queue, message_type, message_id, document_id, body,
                                                  status)
                        VALUES (:id, 'DLQ', 'rpa.post-to-portal.q', 'PostToPortal', :mid, :doc, '{}', 'OPEN')
                        """)
                .param("id", UUID.randomUUID()).param("mid", UUID.randomUUID()).param("doc", stuck).update();
        detector.detect();

        ResponseEntity<String> response = scrape(ADMIN);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .contains("invoice_documents_stuck{application=\"document-service\",status=\"RECEIVED\"} 1.0")
                .contains("invoice_documents_stuck{application=\"document-service\",status=\"VALIDATED\"} 0.0")
                .contains("invoice_dead_letters_open{application=\"document-service\",kind=\"DLQ\"} 1.0")
                .contains("invoice_outbox_pending{application=\"document-service\"}")
                .contains("invoice_outbox_oldest_pending_age_seconds{application=\"document-service\"}");
        assertThat(scrape(EXPERT).getStatusCode().value()).isEqualTo(403);
    }

    private ResponseEntity<String> scrape(String user) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(basicAuth(user))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build()
                .get().uri("/actuator/prometheus")
                .retrieve().toEntity(String.class);
    }
}
