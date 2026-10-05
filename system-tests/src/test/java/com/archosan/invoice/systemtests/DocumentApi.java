package com.archosan.invoice.systemtests;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * document-service'in dışa açık API'si (§4.1), yalnızca sistem testlerinin kullandığı kadarı. Uzman kullanıcısıyla
 * (HTTP Basic, B-43) çağrılır: yükleme, okuma ve mükerrer kararı onun rolündedir.
 */
final class DocumentApi {

    /** İnsan veya yönetici adımı olmadan değişmeyen durumlar (v1). */
    static final Set<String> SETTLED = Set.of("POSTED", "NEEDS_REVIEW", "PENDING_APPROVAL", "REJECTED",
            "DUPLICATE_SUSPECTED", "RPA_FAILED");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private volatile String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String authorization;

    DocumentApi(String baseUrl, String username, String password) {
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        rebase(baseUrl);
    }

    /** Servis yeniden başlayınca Docker rastgele host portunu yeniden atar; istemci yeni adrese yönlenir. */
    void rebase(String baseUrl) {
        this.base = baseUrl + "/api/v1/documents";
    }

    record Upload(int httpStatus, UUID documentId, String status, boolean duplicate) {
    }

    Upload upload(String fileName, byte[] pdf) {
        String boundary = "----system-tests-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(pdf);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build());
        JsonNode json = JSON.readTree(response.body());
        return new Upload(response.statusCode(), UUID.fromString(json.path("documentId").asString()),
                json.path("status").asString(), json.path("duplicate").asBoolean());
    }

    JsonNode get(UUID documentId) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/" + documentId)).GET().build());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + documentId + " → " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    /** Mükerrer şüphesine karar (B-41); HTTP durum kodunu döner. */
    int decideDuplicate(UUID documentId, boolean duplicate, String reason) {
        String body = JSON.writeValueAsString(reason == null
                ? Map.of("duplicate", duplicate)
                : Map.of("duplicate", duplicate, "reason", reason));
        return send(HttpRequest.newBuilder(URI.create(base + "/" + documentId + "/duplicate-decision"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()).statusCode();
    }

    String status(UUID documentId) {
        return get(documentId).path("status").asString();
    }

    /** Kayıt değişmeyen bir duruma gelene kadar bekler ve o durumu döner. */
    String awaitSettled(UUID documentId, Duration timeout) {
        return await().atMost(timeout).pollInterval(Duration.ofSeconds(1))
                .until(() -> status(documentId), SETTLED::contains);
    }

    private HttpResponse<String> send(HttpRequest request) {
        HttpRequest authorized = HttpRequest.newBuilder(request, (name, value) -> true)
                .header("Authorization", authorization)
                .build();
        try {
            return http.send(authorized, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
