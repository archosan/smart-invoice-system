package com.archosan.invoice.mockportal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PortalProperties.class)
public class MockPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPortalApplication.class, args);
    }
}
