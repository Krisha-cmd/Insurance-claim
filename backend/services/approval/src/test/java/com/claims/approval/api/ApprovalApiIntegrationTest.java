package com.claims.approval.api;

import com.claims.approval.domain.CoordinatorState;
import com.claims.approval.repository.ApprovalDecisionRepository;
import com.claims.approval.service.ChaosMode;
import com.claims.approval.support.IntegrationTestBase;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.UpdateStatusRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The REST contract (ENDPOINTS.approval) through the full security chain. */
@AutoConfigureMockMvc
class ApprovalApiIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApprovalDecisionRepository decisions;

    private static RequestPostProcessor adjuster() {
        return jwt().jwt(j -> j.subject(ADJUSTER.id().toString()).claim("role", "ADJUSTER").claim("name", "Rohan Mehta"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADJUSTER"));
    }

    private static RequestPostProcessor policyholder() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "POLICYHOLDER").claim("name", "Asha Verma"))
                .authorities(new SimpleGrantedAuthority("ROLE_POLICYHOLDER"));
    }

    private ResultActions decide(UUID claimId, String decision, String comment, RequestPostProcessor who) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/approvals/{id}", claimId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\",\"comment\":" + mapper.writeValueAsString(comment) + "}");
        return mvc.perform(who == null ? req : req.with(who));
    }

    @Test
    void rejectReturns200AndSetsTheClaimToRejected() throws Exception {
        UUID claimId = claimUnderReview("1000.00");

        decide(claimId, "REJECTED", "Treatment is excluded under the plan.", adjuster())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(claimId.toString()))
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.txId").value(nullValue()));

        assertThat(CLAIMS.statusOf(claimId)).isEqualTo(ClaimStatus.REJECTED);
        UpdateStatusRequest sent = CLAIMS.requestsFor(claimId).getFirst();
        assertThat(sent.getReason()).isEqualTo("Treatment is excluded under the plan.");
        assertThat(sent.getActor().getId()).isEqualTo(ADJUSTER.id().toString());
        assertThat(sent.getActor().getRole()).isEqualTo("ADJUSTER");
        assertThat(events().typesFor(claimId)).containsExactly("CLAIM_REJECTED");
        assertThat(txs.findFirstByClaimIdOrderByAttemptDesc(claimId)).isEmpty();

        mvc.perform(get("/api/approvals/{id}/settlement", claimId).with(adjuster())).andExpect(status().isNoContent());
    }

    @Test
    void approveReturns202WithTxIdAndTheSettlementViewMatchesTheStepper() throws Exception {
        UUID claimId = claimUnderReview("67500.00");

        String body = decide(claimId, "APPROVED", "Bills match the assessment.", adjuster())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.attempt").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID txId = UUID.fromString(mapper.readTree(body).get("txId").asText());
        assertThat(decisions.findByClaimId(claimId)).get()
                .satisfies(d -> assertThat(d.getAmount()).isEqualByComparingTo("67500.00"));

        awaitState(txId, CoordinatorState.COMMITTED);

        String view = mvc.perform(get("/api/approvals/{id}/settlement", claimId).with(adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(txId.toString()))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.phase").value("DONE"))
                .andExpect(jsonPath("$.outcome").value("COMMITTED"))
                .andExpect(jsonPath("$.state").value("COMMITTED"))
                .andExpect(jsonPath("$.coordinator").value("UP"))
                .andExpect(jsonPath("$.amount").value(67500.00))
                .andExpect(jsonPath("$.abortReason").value(nullValue()))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.participants", hasSize(3)))
                .andExpect(jsonPath("$.participants[0].name").value("CLAIM_INTAKE"))
                .andExpect(jsonPath("$.participants[1].name").value("POLICY"))
                .andExpect(jsonPath("$.participants[2].name").value("PAYMENT"))
                .andReturn().getResponse().getContentAsString();

        JsonNode v = mapper.readTree(view);
        for (JsonNode p : v.get("participants")) {
            assertThat(p.get("vote").asText()).isEqualTo("YES");
            assertThat(p.get("state").asText()).isEqualTo("COMMITTED");
            assertThat(p.get("preCommitAck").asBoolean()).isTrue();
            assertThat(p.get("doCommitAck").asBoolean()).isTrue();
            assertThat(p.get("aborted").asBoolean()).isFalse();
        }
        assertThat(v.get("log").size()).isGreaterThanOrEqualTo(3);
        assertThat(v.get("log").get(0).get("level").asText()).isEqualTo("info");
    }

    @Test
    void aSecondDecisionIs409() throws Exception {
        UUID claimId = claimUnderReview("500.00");
        decide(claimId, "REJECTED", "Duplicate of an earlier claim.", adjuster()).andExpect(status().isOk());

        decide(claimId, "APPROVED", "Changed my mind about this one.", adjuster())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A decision has already been recorded for this claim."));
    }

    @Test
    void approvingAnUnassessedClaimIs409AndRecordsNothing() throws Exception {
        UUID claimId = UUID.randomUUID();
        CLAIMS.claim(claimId, ClaimStatus.UNDER_REVIEW);

        decide(claimId, "APPROVED", "Looks fine to me overall.", adjuster())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The claim has not been assessed yet."));
        assertThat(decisions.findByClaimId(claimId)).isEmpty();
        assertThat(CLAIMS.requestsFor(claimId)).isEmpty();
    }

    @Test
    void whenClaimIntakeRefusesTheStatusChangeTheDecisionIsRolledBack() throws Exception {
        UUID claimId = claimUnderReview("900.00");
        CLAIMS.refuseUpdates(claimId);

        decide(claimId, "APPROVED", "Bills match the assessment.", adjuster())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Claim is not under review."));
        assertThat(decisions.findByClaimId(claimId)).isEmpty();
        assertThat(txs.findFirstByClaimIdOrderByAttemptDesc(claimId)).isEmpty();
        assertThat(events().typesFor(claimId)).isEmpty();
    }

    @Test
    void validationErrorsAre400WithReadableMessages() throws Exception {
        UUID claimId = claimUnderReview("900.00");
        decide(claimId, "APPROVED", "too short", adjuster())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("A justification comment of at least 10 characters is required."));
        decide(claimId, "MAYBE", "A perfectly long comment.", adjuster())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Decision must be APPROVED or REJECTED."));
        mvc.perform(post("/api/approvals/not-a-uuid").with(adjuster())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECTED\",\"comment\":\"Long enough comment\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for claimId."));
        assertThat(decisions.findByClaimId(claimId)).isEmpty();
    }

    @Test
    void onlyAdjustersMayUseTheApi() throws Exception {
        UUID claimId = claimUnderReview("900.00");
        decide(claimId, "REJECTED", "Treatment is excluded.", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        decide(claimId, "REJECTED", "Treatment is excluded.", policyholder())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only adjusters can do that."));
        mvc.perform(get("/api/approvals/{id}/settlement", claimId).with(policyholder())).andExpect(status().isForbidden());
        mvc.perform(post("/api/approvals/{id}/settlement/retry", claimId).with(policyholder())).andExpect(status().isForbidden());
        assertThat(CLAIMS.requestsFor(claimId)).isEmpty();
    }

    @Test
    void retryIsOnlyAllowedAfterAnAbort() throws Exception {
        UUID claimId = claimUnderReview("900.00");
        mvc.perform(post("/api/approvals/{id}/settlement/retry", claimId).with(adjuster()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No decision has been recorded for this claim."));

        decide(claimId, "REJECTED", "Treatment is excluded.", adjuster()).andExpect(status().isOk());
        mvc.perform(post("/api/approvals/{id}/settlement/retry", claimId).with(adjuster()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only approved claims can be settled."));

        PAYMENT.voteNo("Gateway down");
        UUID approved = claimUnderReview("700.00");
        String body = decide(approved, "APPROVED", "Bills match the assessment.", adjuster())
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        awaitState(UUID.fromString(mapper.readTree(body).get("txId").asText()), CoordinatorState.ABORTED);
        PAYMENT.reset();

        mvc.perform(post("/api/approvals/{id}/settlement/retry", approved).with(adjuster()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.txId").isNotEmpty());
    }

    @Test
    void internalChaosSwitch() throws Exception {
        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"CRASH_AFTER_PRECOMMIT\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"mode\":\"CRASH_AFTER_PRECOMMIT\"}"));
        assertThat(chaos.mode()).isEqualTo(ChaosMode.CRASH_AFTER_PRECOMMIT);
        mvc.perform(get("/internal/chaos")).andExpect(jsonPath("$.mode").value("CRASH_AFTER_PRECOMMIT"));

        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"EXPLODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("mode must be NONE, CRASH_BEFORE_PRECOMMIT or CRASH_AFTER_PRECOMMIT."));

        mvc.perform(post("/internal/chaos").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NONE\"}"))
                .andExpect(jsonPath("$.mode").value("NONE"));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
