package com.archosan.invoice.document.maintenance;

import com.archosan.invoice.document.DocumentIntegrationTest;
import com.archosan.invoice.document.DocumentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Varsayılan eşiklerle: RECEIVED 30 dk, VALIDATED 10 dk, QUEUED_FOR_RPA 30 dk. */
@ExtendWith(OutputCaptureExtension.class)
class StuckDocumentDetectorTest extends DocumentIntegrationTest {

    @Autowired
    private StuckDocumentDetector detector;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void reportsRecordsOverPerStateThresholdInOneErrorLine(CapturedOutput output) {
        UUID oldReceived = insert(DocumentStatus.RECEIVED, 45);
        insert(DocumentStatus.RECEIVED, 20);                      // 30 dk eşiğinin altında
        UUID oldValidated = insert(DocumentStatus.VALIDATED, 15);
        insert(DocumentStatus.QUEUED_FOR_RPA, 20);                // 30 dk eşiğinin altında

        Map<DocumentStatus, StuckDocumentDetector.Stuck> stuck = detector.detect();

        assertThat(stuck).containsOnlyKeys(DocumentStatus.RECEIVED, DocumentStatus.VALIDATED);
        assertThat(stuck.get(DocumentStatus.RECEIVED).count()).isEqualTo(1);
        assertThat(stuck.get(DocumentStatus.RECEIVED).oldest()).containsExactly(oldReceived);
        assertThat(stuck.get(DocumentStatus.VALIDATED).oldest()).containsExactly(oldValidated);
        assertThat(output.getOut()).contains("Takılı kayıtlar: RECEIVED=1", "VALIDATED=1", oldReceived.toString());
    }

    @Test
    void humanWaitingStatesAreNotStuck(CapturedOutput output) {
        insert(DocumentStatus.NEEDS_REVIEW, 600);
        insert(DocumentStatus.PENDING_APPROVAL, 600);
        insert(DocumentStatus.RPA_FAILED, 600);
        insert(DocumentStatus.POSTED, 600);

        assertThat(detector.detect()).isEmpty();
        assertThat(output.getOut()).doesNotContain("Takılı kayıtlar");
    }

    @Test
    void reportsAtMostSampleSizeOldestIdsButFullCount() {
        UUID oldest = insert(DocumentStatus.QUEUED_FOR_RPA, 500);
        for (int i = 0; i < 11; i++) {
            insert(DocumentStatus.QUEUED_FOR_RPA, 60 + i);
        }

        StuckDocumentDetector.Stuck stuck = detector.detect().get(DocumentStatus.QUEUED_FOR_RPA);

        assertThat(stuck.count()).isEqualTo(12);
        assertThat(stuck.oldest()).hasSize(10).first().isEqualTo(oldest);
    }

    private UUID insert(DocumentStatus status, int minutesSinceUpdate) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO documents (id, file_sha256, storage_uri, status, updated_at)
                        VALUES (:id, :sha, 'file:///x.pdf', :status, now() - make_interval(mins => :minutes))
                        """)
                .param("id", id).param("sha", (id.toString() + id).replace("-", "").substring(0, 64))
                .param("status", status.name()).param("minutes", minutesSinceUpdate).update();
        return id;
    }
}
