package com.claims.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClaimIntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimIntakeApplication.class, args);
    }
}
