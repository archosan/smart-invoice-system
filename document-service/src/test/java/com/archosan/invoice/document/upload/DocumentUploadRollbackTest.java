package com.archosan.invoice.document.upload;

import com.archosan.invoice.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Outbox yazımı başarısız olursa transaction geri alınır: kayıt, geçiş ve dosya kalmaz. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentUploadRollbackTest extends UploadTestSupport {

    @MockitoBean
    private OutboxWriter outbox;

    @Test
    void failureInsideTransactionLeavesNoRecordAndNoFile() throws Exception {
        when(outbox.add(any(UUID.class), any())).thenThrow(new IllegalStateException("outbox yazılamadı"));
        byte[] content = pdf();

        ResponseEntity<String> response = upload(content);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(count("documents")).isZero();
        assertThat(count("status_transitions")).isZero();
        assertThat(STORAGE_DIR.resolve(sha256(content) + ".pdf")).doesNotExist();
        assertThat(stagingFileCount()).isZero();
    }
}
