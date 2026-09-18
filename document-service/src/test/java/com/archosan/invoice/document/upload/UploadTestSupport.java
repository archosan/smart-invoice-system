package com.archosan.invoice.document.upload;

import com.archosan.invoice.document.DocumentIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

/** Gerçek sunucuya multipart yükleme yapan testlerin ortak yardımcıları. */
abstract class UploadTestSupport extends DocumentIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    protected JdbcClient jdbc;

    protected final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void cleanStorage() throws IOException {
        try (Stream<Path> files = Files.walk(STORAGE_DIR)) {
            files.filter(Files::isRegularFile).forEach(p -> p.toFile().delete());
        }
    }

    protected ResponseEntity<String> upload(byte[] content) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return "fatura.pdf";
            }
        });
        return post(parts);
    }

    protected ResponseEntity<String> post(MultiValueMap<String, Object> parts) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(basicAuth(EXPERT))
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build()
                .post().uri("/api/v1/documents")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .toEntity(String.class);
    }

    protected JsonNode body(ResponseEntity<String> response) {
        return json.readTree(response.getBody());
    }

    protected static byte[] pdf() {
        return ("%PDF-1.7\n% sentetik fatura " + UUID.randomUUID() + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    protected static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    protected static long stagingFileCount() throws IOException {
        Path staging = STORAGE_DIR.resolve(".staging");
        try (Stream<Path> files = Files.list(staging)) {
            return files.count();
        }
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
