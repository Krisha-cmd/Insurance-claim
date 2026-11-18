package com.claims.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GatewayControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ChaosState state;
    @Autowired ObjectMapper json;

    @BeforeEach
    void reset() {
        state.reset();
    }

    private ResultActions call(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String authorize(UUID paymentId) throws Exception {
        String res = call("/authorize", "{\"paymentId\":\"" + paymentId + "\",\"amount\":4500.00}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("authId").asText();
    }

    private void chaos(String mode, long delayMs) throws Exception {
        call("/chaos", "{\"mode\":\"" + mode + "\",\"delayMs\":" + delayMs + "}").andExpect(status().isOk());
    }

    @Test
    void none_authorizeCaptureVoid_andTheyAreIdempotent() throws Exception {
        UUID payment = UUID.randomUUID();
        String authId = authorize(payment);
        assertThat(authId).startsWith("AUTH-");
        assertThat(authorize(payment)).as("same paymentId, same authorization").isEqualTo(authId);

        String first = call("/capture", "{\"authId\":\"" + authId + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.captureRef").value(org.hamcrest.Matchers.startsWith("UTR")))
                .andReturn().getResponse().getContentAsString();
        String again = call("/capture", "{\"authId\":\"" + authId + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(again).as("capture is idempotent").isEqualTo(first);
        call("/void", "{\"authId\":\"" + authId + "\"}").andExpect(status().isConflict());

        String other = authorize(UUID.randomUUID());
        call("/void", "{\"authId\":\"" + other + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
        call("/void", "{\"authId\":\"" + other + "\"}").andExpect(status().isOk());
        call("/capture", "{\"authId\":\"" + other + "\"}").andExpect(status().isConflict());
        call("/capture", "{\"authId\":\"AUTH-NOPE\"}").andExpect(status().isNotFound());
    }

    @Test
    void failAuthorize_answers503() throws Exception {
        chaos("FAIL_AUTHORIZE", 0);
        call("/authorize", "{\"paymentId\":\"" + UUID.randomUUID() + "\",\"amount\":100}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Authorization declined: gateway unavailable (simulated)"));
    }

    @Test
    void failCapture_authorizesButCaptureAnswers503_untilReset() throws Exception {
        String authId = authorize(UUID.randomUUID());
        chaos("FAIL_CAPTURE", 0);
        authorize(UUID.randomUUID()); // authorize still works
        call("/capture", "{\"authId\":\"" + authId + "\"}").andExpect(status().isServiceUnavailable());
        call("/void", "{\"authId\":\"" + authId + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));

        String later = authorize(UUID.randomUUID());
        chaos("NONE", 0);
        call("/capture", "{\"authId\":\"" + later + "\"}").andExpect(status().isOk());
    }

    @Test
    void slow_waitsBeforeAnswering() throws Exception {
        chaos("SLOW", 600);
        long start = System.nanoTime();
        authorize(UUID.randomUUID());
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(600);
    }

    @Test
    void chaosCanBeReadAndIsValidated() throws Exception {
        chaos("SLOW", 250);
        mvc.perform(get("/chaos")).andExpect(jsonPath("$.mode").value("SLOW")).andExpect(jsonPath("$.delayMs").value(250));
        call("/chaos", "{\"mode\":\"EXPLODE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mode must be NONE, FAIL_AUTHORIZE, FAIL_CAPTURE or SLOW"));
        call("/chaos", "{\"mode\":\"SLOW\",\"delayMs\":99999999}").andExpect(jsonPath("$.delayMs").value(60000));
        call("/chaos", "{\"mode\":\"NONE\"}").andExpect(jsonPath("$.mode").value("NONE"));
    }

    @Test
    void badRequestsAre400() throws Exception {
        call("/authorize", "{\"paymentId\":\"" + UUID.randomUUID() + "\",\"amount\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("amount must be positive"));
        call("/capture", "{}").andExpect(status().isBadRequest());
    }
}
