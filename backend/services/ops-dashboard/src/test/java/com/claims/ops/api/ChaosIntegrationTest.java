package com.claims.ops.api;

import com.claims.common.event.ClaimEvent;
import com.claims.common.event.EventPublisher;
import com.claims.ops.support.OpsContainers;
import com.claims.ops.support.OpsTest;
import com.claims.ops.support.RecordingEventPublisher;
import com.claims.ops.support.Shapes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/ops/chaos reaches the right internal endpoint (WireMock) and returns the combined status. */
@OpsTest
class ChaosIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EventPublisher events;

    @BeforeEach
    void services() {
        OpsContainers.APPROVAL.resetAll();
        OpsContainers.PAYMENT.resetAll();
        OpsContainers.APPROVAL.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/internal/chaos"))
                .willReturn(okJson("{\"mode\":\"NONE\"}")));
        OpsContainers.APPROVAL.stubFor(post(urlEqualTo("/internal/chaos")).willReturn(okJson("{\"mode\":\"CRASH_AFTER_PRECOMMIT\"}")));
        OpsContainers.PAYMENT.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/internal/chaos"))
                .willReturn(okJson("{\"mode\":\"NONE\",\"delayMs\":0}")));
        OpsContainers.PAYMENT.stubFor(post(urlEqualTo("/internal/chaos")).willReturn(okJson("{\"mode\":\"FAIL_AUTHORIZE\",\"delayMs\":0}")));
    }

    private ResultActions set(String target, String mode) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ops/chaos")
                .with(OpsDashboardIntegrationTest.adjuster())
                .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"" + target + "\",\"mode\":\"" + mode + "\"}"));
    }

    @Test
    void paymentFail_reachesPaymentAsFailAuthorize_andReturnsBothTargets() throws Exception {
        OpsContainers.PAYMENT.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/internal/chaos"))
                .willReturn(okJson("{\"mode\":\"FAIL_AUTHORIZE\",\"delayMs\":0}")));

        String res = set("PAYMENT", "FAIL").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        OpsContainers.PAYMENT.verify(postRequestedFor(urlEqualTo("/internal/chaos"))
                .withRequestBody(equalToJson("{\"mode\":\"FAIL_AUTHORIZE\",\"delayMs\":0}")));
        assertThat(OpsContainers.APPROVAL.findAll(postRequestedFor(urlEqualTo("/internal/chaos")))).isEmpty();

        JsonNode status = json.readTree(res);
        Shapes.assertCovers("chaosAfterSet", status, Set.of());
        assertThat(status.get("PAYMENT").get("mode").asText()).isEqualTo("FAIL");
        assertThat(status.get("PAYMENT").get("serviceMode").asText()).isEqualTo("FAIL_AUTHORIZE");
        assertThat(status.get("PAYMENT").get("changedBy").get("name").asText()).isEqualTo("Rohan Mehta");
        assertThat(status.get("PAYMENT").get("changedAt").asText()).isNotBlank();
        assertThat(status.get("COORDINATOR").get("mode").asText()).isEqualTo("OFF");

        ClaimEvent changed = ((RecordingEventPublisher) events).all().stream()
                .filter(e -> e.eventType().equals("CHAOS_CHANGED")).reduce((a, b) -> b).orElseThrow();
        assertThat(changed.change()).containsEntry("target", "PAYMENT").containsEntry("mode", "FAIL")
                .containsEntry("serviceMode", "FAIL_AUTHORIZE");
        assertThat(changed.actor().name()).isEqualTo("Rohan Mehta");
    }

    @Test
    void coordinatorCrash_reachesApproval() throws Exception {
        set("COORDINATOR", "CRASH_AFTER_PRECOMMIT").andExpect(status().isOk());
        OpsContainers.APPROVAL.verify(postRequestedFor(urlEqualTo("/internal/chaos"))
                .withRequestBody(equalToJson("{\"mode\":\"CRASH_AFTER_PRECOMMIT\"}")));

        set("COORDINATOR", "OFF").andExpect(status().isOk());
        OpsContainers.APPROVAL.verify(postRequestedFor(urlEqualTo("/internal/chaos")).withRequestBody(equalToJson("{\"mode\":\"NONE\"}")));
    }

    @Test
    void getReadsBothServices() throws Exception {
        String res = mvc.perform(get("/api/ops/chaos").with(OpsDashboardIntegrationTest.adjuster()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode status = json.readTree(res);
        Shapes.assertCovers("chaos", status, Set.of());
        assertThat(status.get("PAYMENT").get("mode").asText()).isEqualTo("OFF");
        assertThat(status.get("PAYMENT").get("reachable").asBoolean()).isTrue();
    }

    @Test
    void invalidTargetsAndModesAre400() throws Exception {
        set("DATABASE", "FAIL").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid chaos target or mode."));
        set("PAYMENT", "CRASH_AFTER_PRECOMMIT").andExpect(status().isBadRequest());
        set("COORDINATOR", "FAIL").andExpect(status().isBadRequest());
    }

    @Test
    void anUnreachableServiceIsReportedNotFatal() throws Exception {
        OpsContainers.APPROVAL.resetAll();
        OpsContainers.APPROVAL.stubFor(com.github.tomakehurst.wiremock.client.WireMock.any(urlEqualTo("/internal/chaos"))
                .willReturn(serviceUnavailable()));

        mvc.perform(get("/api/ops/chaos").with(OpsDashboardIntegrationTest.adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.COORDINATOR.mode").value("UNKNOWN"))
                .andExpect(jsonPath("$.COORDINATOR.reachable").value(false))
                .andExpect(jsonPath("$.PAYMENT.mode").value("OFF"));
        set("COORDINATOR", "CRASH_AFTER_PRECOMMIT").andExpect(status().isBadGateway());
    }
}
