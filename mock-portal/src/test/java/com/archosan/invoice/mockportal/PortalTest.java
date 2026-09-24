package com.archosan.invoice.mockportal;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/** Gerçek port üzerinde portal; kimlik bilgileri her çalıştırmada rastgele (koda ve teste secret yazılmaz). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class PortalTest {

    static final String USERNAME = "rpa-bot";
    static final String PASSWORD = UUID.randomUUID().toString();

    @LocalServerPort
    private int port;

    PortalClient client;

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("mock-portal.username", () -> USERNAME);
        registry.add("mock-portal.password", () -> PASSWORD);
    }

    @BeforeEach
    void newBrowser() {
        client = new PortalClient(port);
    }

    PortalClient loggedIn() {
        client.login(USERNAME, PASSWORD);
        return client;
    }
}
