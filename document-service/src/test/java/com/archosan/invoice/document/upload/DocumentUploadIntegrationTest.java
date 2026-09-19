package com.archosan.invoice.document.upload;

import com.archosan.invoice.messaging.message.ExtractInvoice;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Boyut sınırı testi küçük bir dosyayla yapılsın; davranış 20 MB ile aynıdır.
@TestPropertySource(properties = {
        "spring.servlet.multipart.max-file-size=64KB",
        "spring.servlet.multipart.max-request-size=65KB"})
class DocumentUploadIntegrationTest extends UploadTestSupport {

    @Test
    void newUploadCreatesRecordTransitionOutboxAndStoresFile() throws Exception {
        byte[] content = pdf();
        String sha = sha256(content);

        ResponseEntity<String> response = upload(content);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = body(response);
        UUID documentId = UUID.fromString(body.path("documentId").asString());
        assertThat(body.path("status").asString()).isEqualTo("RECEIVED");
        assertThat(body.path("duplicate").asBoolean()).isFalse();
        assertThat(response.getHeaders().getLocation()).hasPath("/api/v1/documents/" + documentId);

        Path stored = STORAGE_DIR.resolve(sha + ".pdf").toAbsolutePath();
        assertThat(stored).exists().hasBinaryContent(content);
        assertThat(stagingFileCount()).isZero();

        assertThat(jdbc.sql("SELECT status, file_sha256, storage_uri FROM documents WHERE id = :id")
                .param("id", documentId)
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)))
                .single())
                .containsExactly("RECEIVED", sha, stored.toUri().toString());
        assertThat(jdbc.sql("""
                        SELECT coalesce(from_status, '-') || '>' || to_status || ':' || trigger_event || ':' || actor
                        FROM status_transitions WHERE document_id = :id
                        """)
                .param("id", documentId).query(String.class).list())
                .containsExactly("->RECEIVED:UPLOAD:expert");

        String payload = jdbc.sql("""
                        SELECT payload::text FROM outbox
                        WHERE aggregate_id = :id AND message_type = 'ExtractInvoice' AND routing_key = 'extract.invoice'
                        """)
                .param("id", documentId).query(String.class).single();
        ExtractInvoice command = json.readValue(payload, ExtractInvoice.class);
        assertThat(command).isEqualTo(new ExtractInvoice(documentId, stored.toUri().toString(), sha));
    }

    @Test
    void sameFileAgainReturnsExistingRecordWithoutSideEffects() throws Exception {
        byte[] content = pdf();
        UUID first = UUID.fromString(body(upload(content)).path("documentId").asString());

        ResponseEntity<String> again = upload(content);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = body(again);
        assertThat(body.path("documentId").asString()).isEqualTo(first.toString());
        assertThat(body.path("status").asString()).isEqualTo("RECEIVED");
        assertThat(body.path("duplicate").asBoolean()).isTrue();
        assertThat(count("documents")).isEqualTo(1);
        assertThat(count("status_transitions")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(stagingFileCount()).isZero();
    }

    @Test
    void concurrentUploadsOfSameFileCreateOneRecord() throws Exception {
        byte[] content = pdf();

        List<CompletableFuture<ResponseEntity<String>>> uploads = List.of(
                CompletableFuture.supplyAsync(() -> upload(content)),
                CompletableFuture.supplyAsync(() -> upload(content)),
                CompletableFuture.supplyAsync(() -> upload(content)));
        List<ResponseEntity<String>> responses = uploads.stream().map(CompletableFuture::join).toList();

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.ACCEPTED, HttpStatus.OK)
                .filteredOn(HttpStatus.ACCEPTED::equals).hasSize(1);
        assertThat(responses).extracting(r -> body(r).path("documentId").asString()).containsOnly(
                body(responses.getFirst()).path("documentId").asString());
        assertThat(count("documents")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(stagingFileCount()).isZero();
    }

    @Test
    void rejectsNonPdfWithProblemDetail() throws Exception {
        ResponseEntity<String> response = upload("PK\u0003\u0004 bu bir zip".getBytes());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(body(response).path("status").asInt()).isEqualTo(415);
        assertThat(count("documents")).isZero();
        assertThat(stagingFileCount()).isZero();
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        ResponseEntity<String> response = upload(new byte[0]);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(count("documents")).isZero();
    }

    @Test
    void rejectsFileOverSizeLimitWithProblemDetail() {
        byte[] large = new byte[100 * 1024];
        System.arraycopy("%PDF-".getBytes(), 0, large, 0, 5);

        ResponseEntity<String> response = upload(large);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(count("documents")).isZero();
    }

    @Test
    void fileFarOverSizeLimitStillGets413InsteadOfConnectionReset() {
        // Tomcat'in varsayılan max-swallow-size'ı (2 MB) aşılıyor; application.yml'daki ayar olmadan bağlantı kopar.
        byte[] large = new byte[8 * 1024 * 1024];
        System.arraycopy("%PDF-".getBytes(), 0, large, 0, 5);

        ResponseEntity<String> response = upload(large);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void rejectsRequestWithoutFilePart() {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("other", "değer");

        ResponseEntity<String> response = post(parts);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
    }

    @Test
    void storedFileIsOutsideStagingDirectory() throws Exception {
        byte[] content = pdf();
        upload(content);

        try (var files = Files.list(STORAGE_DIR)) {
            assertThat(files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
                    .containsExactly(sha256(content) + ".pdf");
        }
    }
}
