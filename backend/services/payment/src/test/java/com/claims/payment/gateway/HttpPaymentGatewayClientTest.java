package com.claims.payment.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How the REST client turns gateway answers and failures into GatewayException codes (no Spring context). */
class HttpPaymentGatewayClientTest {

    static WireMockServer wm;
    HttpPaymentGatewayClient client;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        client = clientFor(wm.baseUrl());
    }

    private static HttpPaymentGatewayClient clientFor(String baseUrl) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(500);
        f.setReadTimeout(500);
        return new HttpPaymentGatewayClient(RestClient.builder().baseUrl(baseUrl).requestFactory(f).build(), new ObjectMapper());
    }

    private GatewayException failure(Runnable call) {
        try {
            call.run();
        } catch (GatewayException e) {
            return e;
        }
        throw new AssertionError("expected a GatewayException");
    }

    @Test
    void success() {
        wm.stubFor(post("/authorize").willReturn(okJson("{\"authId\":\"AUTH-1\"}")));
        wm.stubFor(post("/capture").willReturn(okJson("{\"captureRef\":\"UTR1\"}")));
        wm.stubFor(post("/void").willReturn(okJson("{}")));
        assertThat(client.authorize(UUID.randomUUID(), BigDecimal.TEN).reference()).isEqualTo("AUTH-1");
        assertThat(client.capture("AUTH-1").reference()).isEqualTo("UTR1");
        assertThat(client.voidAuth("AUTH-1").reference()).isEqualTo("AUTH-1");
    }

    @Test
    void a5xxIsRetryable_withTheGatewaysMessage() {
        wm.stubFor(post("/capture").willReturn(aResponse().withStatus(503).withHeader("Content-Type", "application/json")
                .withBody("{\"status\":503,\"message\":\"Capture failed: gateway unavailable (simulated)\"}")));
        GatewayException e = failure(() -> client.capture("AUTH-1"));
        assertThat(e.code()).isEqualTo("HTTP_503");
        assertThat(e.retryable()).isTrue();
        assertThat(e.getMessage()).isEqualTo("Capture failed: gateway unavailable (simulated)");
    }

    @Test
    void a4xxIsNotRetryable() {
        wm.stubFor(post("/capture").willReturn(aResponse().withStatus(409).withBody("voided")));
        GatewayException e = failure(() -> client.capture("AUTH-1"));
        assertThat(e.code()).isEqualTo("HTTP_409");
        assertThat(e.retryable()).isFalse();
        assertThat(e.getMessage()).isEqualTo("Gateway answered 409");
    }

    @Test
    void aSlowAnswerIsATimeout() {
        wm.stubFor(post("/authorize").willReturn(okJson("{\"authId\":\"AUTH-1\"}").withFixedDelay(1500)));
        GatewayException e = failure(() -> client.authorize(UUID.randomUUID(), BigDecimal.TEN));
        assertThat(e.code()).isEqualTo("TIMEOUT");
        assertThat(e.retryable()).isTrue();
    }

    @Test
    void anUnreachableGatewayIsANetworkError() {
        GatewayException e = failure(() -> clientFor("http://localhost:1").authorize(UUID.randomUUID(), BigDecimal.TEN));
        assertThat(e.code()).isEqualTo("NETWORK");
        assertThat(e.retryable()).isTrue();
    }

    @Test
    void anAnswerWithoutTheFieldIsABadResponse() {
        wm.stubFor(post("/authorize").willReturn(okJson("{\"something\":\"else\"}")));
        assertThatThrownBy(() -> client.authorize(UUID.randomUUID(), BigDecimal.TEN))
                .isInstanceOf(GatewayException.class).hasMessage("Gateway answer to /authorize has no authId");
    }
}
