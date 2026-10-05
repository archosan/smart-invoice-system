package com.archosan.invoice.systemtests;

import com.archosan.invoice.compliance.testdata.SyntheticContract;
import com.archosan.invoice.compliance.testdata.SyntheticContractGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/** compliance-service'in sözleşme API'si (B-45), uzman kullanıcısıyla; yalnızca sistem testlerinin kullandığı kadarı. */
final class ContractApi {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String base;
    private final String authorization;
    private final HttpClient http = HttpClient.newHttpClient();

    ContractApi(String baseUrl, String username, String password) {
        this.base = baseUrl + "/api/v1/contracts";
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Sözleşmeyi üretip yükler ve indekslenmesini ({@code READY}) bekler. */
    UUID uploadAndAwaitReady(SyntheticContract contract) {
        byte[] pdf = render(contract);
        String boundary = "----system-tests-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "supplierVkn", contract.supplierVkn());
        field(body, boundary, "validFrom", contract.validFrom().toString());
        field(body, boundary, "validTo", contract.validTo().toString());
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + contract.file() + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(pdf);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build());
        if (response.statusCode() != 202) {
            throw new IllegalStateException("Sözleşme yüklenemedi (" + response.statusCode() + "): " + response.body());
        }
        UUID id = UUID.fromString(JSON.readTree(response.body()).path("contractId").asString());
        String status = await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(500))
                .until(() -> status(id), s -> !s.equals("INGESTING"));
        if (!status.equals("READY")) {
            throw new IllegalStateException("Sözleşme indekslenemedi: " + contract.file() + " → " + status);
        }
        return id;
    }

    String status(UUID id) {
        JsonNode detail = JSON.readTree(send(HttpRequest.newBuilder(URI.create(base + "/" + id)).GET().build()).body());
        return detail.path("contract").path("status").asString();
    }

    private static byte[] render(SyntheticContract contract) {
        try {
            Path pdf = Files.createTempFile("system-tests-contract", ".pdf");
            SyntheticContractGenerator.write(contract, pdf);
            byte[] bytes = Files.readAllBytes(pdf);
            Files.deleteIfExists(pdf);
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
                + "\r\n").getBytes(StandardCharsets.UTF_8));
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
