package com.archosan.invoice.mockportal;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Tarayıcı gibi davranan küçük istemci: cookie tutar, yönlendirmeyi izlemez (testler {@code Location}'a bakar). */
final class PortalClient {

    private static final Pattern REF_NO = Pattern.compile("id=\"ref-no\">([^<]+)<");

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    PortalClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    HttpResponse<String> post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    HttpResponse<String> login(String username, String password) {
        return post("/login", Map.of("username", username, "password", password));
    }

    /** Formu gönderir, yönlendirmeyi izler ve kayıt sayfasındaki kayıt numarasını döner. */
    String submitInvoice(Map<String, String> form) {
        HttpResponse<String> response = post("/invoices", form);
        if (response.statusCode() != 302) {
            throw new AssertionError("302 bekleniyordu: " + response.statusCode());
        }
        String location = response.headers().firstValue("Location").orElseThrow();
        Matcher m = REF_NO.matcher(get(URI.create(location).getRawPath() + "?created").body());
        if (!m.find()) {
            throw new AssertionError("Kayıt no bulunamadı");
        }
        return m.group(1);
    }

    static Map<String, String> invoice(String invoiceNo) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("supplierVkn", "4810293756");
        form.put("supplierName", "Anadolu Rulman A.Ş.");
        form.put("invoiceNo", invoiceNo);
        form.put("invoiceDate", "2026-09-01");
        form.put("dueDate", "2026-10-01");
        form.put("grandTotal", "5100.00");
        form.put("vatTotal", "850.00");
        form.put("currency", "TRY");
        return form;
    }

    static int count(String html, String needle) {
        int n = 0;
        for (int i = html.indexOf(needle); i >= 0; i = html.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
