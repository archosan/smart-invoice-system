package com.archosan.invoice.extraction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtractionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtractionServiceApplication.class, args);
    }
}
