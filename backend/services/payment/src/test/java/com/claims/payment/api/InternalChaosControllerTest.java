package com.claims.payment.api;

import com.claims.payment.support.GatewayStubs;
import com.claims.payment.support.PaymentTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /internal/chaos forwards to the gateway's /chaos. */
@PaymentTest
class InternalChaosControllerTest {

    @Autowired MockMvc mvc;

    @Test
    void forwardsTheModeToTheGateway() throws Exception {
        var wm = GatewayStubs.gateway().healthy().server();
        wm.stubFor(post(urlEqualTo("/chaos")).willReturn(okJson("{\"mode\":\"FAIL_CAPTURE\",\"delayMs\":0}")));
        wm.stubFor(get(urlEqualTo("/chaos")).willReturn(okJson("{\"mode\":\"FAIL_CAPTURE\",\"delayMs\":0}")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/chaos")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"FAIL_CAPTURE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("FAIL_CAPTURE"));
        wm.verify(postRequestedFor(urlEqualTo("/chaos")).withRequestBody(equalToJson("{\"mode\":\"FAIL_CAPTURE\",\"delayMs\":0}")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/chaos"))
                .andExpect(jsonPath("$.mode").value("FAIL_CAPTURE"));
    }

    @Test
    void rejectsUnknownModes_andReportsAnUnreachableGateway() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/chaos")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"EXPLODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mode must be NONE, FAIL_AUTHORIZE, FAIL_CAPTURE or SLOW"));

        GatewayStubs.gateway().server().resetAll(); // no /chaos stub → 404 from WireMock
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/chaos"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }
}
