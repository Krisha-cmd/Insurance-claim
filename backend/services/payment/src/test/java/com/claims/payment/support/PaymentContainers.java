package com.claims.payment.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** Postgres 16 and a WireMock server standing in for the payment gateway, started once per test run. */
public interface PaymentContainers {

    @ServiceConnection
    PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    WireMockServer GATEWAY = Starter.gateway();

    @DynamicPropertySource
    static void gatewayUrl(DynamicPropertyRegistry r) {
        r.add("claims.payment.gateway.base-url", GATEWAY::baseUrl);
    }

    final class Starter {
        private Starter() {
        }

        static WireMockServer gateway() {
            WireMockServer s = new WireMockServer(options().dynamicPort().globalTemplating(true));
            s.start();
            return s;
        }
    }
}
