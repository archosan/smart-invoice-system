package com.archosan.invoice.document.api;

import com.archosan.invoice.document.DocumentIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uç bazlı rol kuralları (B-43, NFR-10): kimliksiz 401, rolü yetmeyen 403. Kontrol iş mantığından önce yapılır;
 * gövdeler bilinçli olarak eksik bırakıldı, 401/403 dışındaki her yanıt rolün geçtiğini gösterir.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "invoice.messaging.outbox.relay.enabled=false")
class ApiSecurityIntegrationTest extends DocumentIntegrationTest {

    private static final String ID = UUID.randomUUID().toString();

    @LocalServerPort
    private int port;

    @Test
    void requestsWithoutOrWithWrongCredentialsAreUnauthorized() {
        assertThat(status(headers -> { }, HttpMethod.GET, "/api/v1/documents")).isEqualTo(401);
        assertThat(status(headers -> headers.setBasicAuth(EXPERT, "yanlis"), HttpMethod.GET, "/api/v1/documents"))
                .isEqualTo(401);
        assertThat(status(headers -> headers.setBasicAuth("kimse", "yanlis"), HttpMethod.GET, "/api/v1/documents"))
                .isEqualTo(401);
    }

    @Test
    void healthIsOpen() {
        assertThat(status(headers -> { }, HttpMethod.GET, "/actuator/health")).isEqualTo(200);
    }

    @Test
    void everyRoleCanRead() {
        for (String user : new String[] {EXPERT, APPROVER, ADMIN}) {
            assertThat(status(basicAuth(user), HttpMethod.GET, "/api/v1/documents")).as(user).isEqualTo(200);
            assertThat(status(basicAuth(user), HttpMethod.GET, "/api/v1/documents/" + ID)).as(user).isEqualTo(404);
            assertThat(status(basicAuth(user), HttpMethod.GET, "/api/v1/documents/" + ID + "/history")).as(user)
                    .isEqualTo(404);
        }
    }

    @Test
    void onlyExpertUploadsCorrectsAndDecidesDuplicates() {
        for (String user : new String[] {APPROVER, ADMIN}) {
            assertThat(status(basicAuth(user), HttpMethod.POST, "/api/v1/documents")).as(user).isEqualTo(403);
            assertThat(status(basicAuth(user), HttpMethod.PUT, "/api/v1/documents/" + ID + "/fields")).as(user)
                    .isEqualTo(403);
            assertThat(status(basicAuth(user), HttpMethod.POST, "/api/v1/documents/" + ID + "/duplicate-decision"))
                    .as(user).isEqualTo(403);
        }
        assertThat(status(basicAuth(EXPERT), HttpMethod.POST, "/api/v1/documents")).isNotIn(401, 403);
    }

    @Test
    void onlyApproverApproves() {
        for (String user : new String[] {EXPERT, ADMIN}) {
            assertThat(status(basicAuth(user), HttpMethod.POST, "/api/v1/documents/" + ID + "/approve")).as(user)
                    .isEqualTo(403);
        }
        assertThat(status(basicAuth(APPROVER), HttpMethod.POST, "/api/v1/documents/" + ID + "/approve"))
                .isEqualTo(404);
        assertThat(status(basicAuth(ADMIN), HttpMethod.POST, "/api/v1/documents/" + ID + "/reject")).isEqualTo(403);
    }

    @Test
    void onlyAdminReachesAdminApi() {
        for (String user : new String[] {EXPERT, APPROVER, EXPERT_APPROVER}) {
            assertThat(status(basicAuth(user), HttpMethod.GET, "/api/v1/admin/settings")).as(user).isEqualTo(403);
            assertThat(status(basicAuth(user), HttpMethod.GET, "/api/v1/admin/dead-letters")).as(user)
                    .isEqualTo(403);
        }
        assertThat(status(basicAuth(ADMIN), HttpMethod.GET, "/api/v1/admin/settings")).isEqualTo(200);
    }

    @Test
    void unknownPathsAreDenied() {
        assertThat(status(basicAuth(ADMIN), HttpMethod.DELETE, "/api/v1/documents/" + ID)).isEqualTo(403);
    }

    private int status(Consumer<HttpHeaders> auth, HttpMethod method, String path) {
        RestClient.RequestBodySpec request = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeaders(auth)
                .defaultStatusHandler(status -> true, (req, response) -> { })
                .build()
                .method(method).uri(path);
        if (method == HttpMethod.PUT || method == HttpMethod.POST) {
            request.contentType(MediaType.APPLICATION_JSON).body("{}");
        }
        return request.retrieve().toBodilessEntity().getStatusCode().value();
    }
}
