package com.claims.payment.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * WireMock stubs that behave like the gateway simulator. The authId echoes the paymentId and the capture
 * reference echoes the authId, so a test can tell calls apart.
 */
public final class GatewayStubs {

    private static final String UNAVAILABLE = "{\"status\":503,\"message\":\"Gateway unavailable (stub)\",\"path\":\"/\"}";

    private final WireMockServer wm;

    public GatewayStubs(WireMockServer wm) {
        this.wm = wm;
    }

    public static GatewayStubs gateway() {
        return new GatewayStubs(PaymentContainers.GATEWAY);
    }

    public WireMockServer server() {
        return wm;
    }

    /** All three calls succeed. */
    public GatewayStubs healthy() {
        wm.resetAll();
        wm.stubFor(post(urlEqualTo("/authorize")).willReturn(okJson("{\"authId\":\"AUTH-{{jsonPath request.body '$.paymentId'}}\"}")));
        wm.stubFor(post(urlEqualTo("/capture")).willReturn(okJson("{\"captureRef\":\"UTR-{{jsonPath request.body '$.authId'}}\"}")));
        wm.stubFor(post(urlEqualTo("/void")).willReturn(okJson("{\"status\":\"VOIDED\"}")));
        return this;
    }

    public GatewayStubs failAuthorize() {
        wm.stubFor(post(urlEqualTo("/authorize")).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json").withBody(UNAVAILABLE)));
        return this;
    }

    public GatewayStubs slowAuthorize(int delayMs) {
        wm.stubFor(post(urlEqualTo("/authorize")).willReturn(okJson("{\"authId\":\"AUTH-SLOW\"}").withFixedDelay(delayMs)));
        return this;
    }

    public GatewayStubs failCaptureAlways() {
        wm.stubFor(post(urlEqualTo("/capture")).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json").withBody(UNAVAILABLE)));
        return this;
    }

    /** /capture answers 503 {@code times} times, then succeeds. */
    public GatewayStubs failCaptureTimes(int times) {
        String state = Scenario.STARTED;
        for (int i = 1; i <= times; i++) {
            String next = "failed-" + i;
            wm.stubFor(post(urlEqualTo("/capture")).inScenario("flaky-capture").whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(503).withHeader("Content-Type", "application/json").withBody(UNAVAILABLE))
                    .willSetStateTo(next));
            state = next;
        }
        wm.stubFor(post(urlEqualTo("/capture")).inScenario("flaky-capture").whenScenarioStateIs(state)
                .willReturn(okJson("{\"captureRef\":\"UTR-{{jsonPath request.body '$.authId'}}\"}")));
        return this;
    }

    public int calls(String path) {
        return wm.findAll(postRequestedFor(urlEqualTo(path))).size();
    }

    public int calls(String path, String jsonField, String value) {
        return wm.findAll(postRequestedFor(urlEqualTo(path)).withRequestBody(matchingJsonPath("$." + jsonField, com.github.tomakehurst.wiremock.client.WireMock.equalTo(value)))).size();
    }
}
