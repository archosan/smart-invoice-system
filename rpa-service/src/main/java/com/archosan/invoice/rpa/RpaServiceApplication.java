package com.archosan.invoice.rpa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RpaProperties.class)
public class RpaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RpaServiceApplication.class, args);
    }
}
