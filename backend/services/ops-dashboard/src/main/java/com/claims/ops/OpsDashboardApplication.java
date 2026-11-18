package com.claims.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OpsDashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsDashboardApplication.class, args);
    }
}
