package com.claims.assessment.api;

import com.claims.assessment.repository.AssessmentRepository;
import com.claims.assessment.support.AssessmentTest;
import com.claims.common.event.ClaimEvent;
import com.claims.proto.assessment.v1.AssessmentServiceGrpc;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.RecordAssessmentRequest;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.UUID;

import static com.claims.assessment.support.AssessmentTestConfig.CLAIMS;
import static com.claims.assessment.support.AssessmentTestConfig.EVENTS;
import static com.claims.assessment.support.AssessmentTestConfig.POLICY;
import static com.claims.assessment.support.TestClaims.PATIENT1_HOLDER;
import static com.claims.assessment.support.TestClaims.PATIENT1_USER;
import static com.claims.assessment.support.TestClaims.assess;
import static com.claims.assessment.support.TestClaims.claim;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ENDPOINTS.assessment, in the shape the adjuster's Assessment tab reads. */
@AssessmentTest
class AssessmentApiIntegrationTest {

    static final UUID ADJUSTER_ID = UUID.fromString("997bacd0-5b29-4554-b398-52e9f89bfa6c");

    static ManagedChannel channel;
    static AssessmentServiceGrpc.AssessmentServiceBlockingStub grpc;

    @Autowired MockMvc mvc;
    @Autowired AssessmentRepository repository;

    @BeforeAll
    static void connect() {
        channel = InProcessChannelBuilder.forName("assessment-under-test").build();
        grpc = AssessmentServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void close() {
        channel.shutdownNow();
    }

    static RequestPostProcessor patient1() {
        return jwt().jwt(j -> j.subject(PATIENT1_USER.toString()).claim("role", "POLICYHOLDER").claim("name", "Asha Verma")
                .claim("policyholderId", PATIENT1_HOLDER.toString())).authorities(new SimpleGrantedAuthority("ROLE_POLICYHOLDER"));
    }

    static RequestPostProcessor otherPolicyholder() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("role", "POLICYHOLDER").claim("name", "Someone Else")
                .claim("policyholderId", UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("ROLE_POLICYHOLDER"));
    }

    static RequestPostProcessor adjuster() {
        return jwt().jwt(j -> j.subject(ADJUSTER_ID.toString()).claim("role", "ADJUSTER").claim("name", "Rohan Mehta"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADJUSTER"));
    }

    /** A claim of 67500 on a fresh standard plan, assessed (proposed 56250) and UNDER_REVIEW at Claim Intake. */
    private UUID assessedClaim() {
        UUID claimId = UUID.randomUUID();
        grpc.assess(assess(claim(claimId, POLICY.newPolicy(), "Hospitalisation", "67500.00")));
        CLAIMS.status(claimId, ClaimStatus.UNDER_REVIEW);
        return claimId;
    }

    private ResultActions adjust(UUID claimId, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(put("/api/assessments/{id}/adjust", claimId).with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void getReturnsTheBreakdownInTheFrontendShape() throws Exception {
        UUID claimId = assessedClaim();

        mvc.perform(get("/api/assessments/{id}", claimId).with(adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(claimId.toString()))
                .andExpect(jsonPath("$.claimedAmount").value(67500.00))
                .andExpect(jsonPath("$.subLimit").value(300000.00))
                .andExpect(jsonPath("$.cappedAmount").value(67500.00))
                .andExpect(jsonPath("$.deductible").value(5000.00))
                .andExpect(jsonPath("$.afterDeductible").value(62500.00))
                .andExpect(jsonPath("$.coPayPercent").value(10.00))
                .andExpect(jsonPath("$.coPayAmount").value(6250.00))
                .andExpect(jsonPath("$.calculatedAmount").value(56250.00))
                .andExpect(jsonPath("$.proposedAmount").value(56250.00))
                .andExpect(jsonPath("$.finalAmount").value(56250.00))
                .andExpect(jsonPath("$.adjusted").value(false))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.lines.length()").value(6))
                .andExpect(jsonPath("$.lines[0].label").value("Claimed amount"))
                .andExpect(jsonPath("$.lines[2].amount").value(-5000.00))
                .andExpect(jsonPath("$.lines[5].label").value("Proposed amount"))
                .andExpect(jsonPath("$.adjustments").isEmpty())
                .andExpect(jsonPath("$.assessedAt").isString());
    }

    @Test
    void theOwnerMayReadButNotAnotherPolicyholder() throws Exception {
        UUID claimId = assessedClaim();

        mvc.perform(get("/api/assessments/{id}", claimId).with(patient1())).andExpect(status().isOk());
        mvc.perform(get("/api/assessments/{id}", claimId).with(otherPolicyholder())).andExpect(status().isForbidden());
        mvc.perform(get("/api/assessments/{id}", claimId)).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnassessedClaimIs404() throws Exception {
        mvc.perform(get("/api/assessments/{id}", UUID.randomUUID()).with(adjuster()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("The claim has not been assessed yet."));
    }

    @Test
    void anAdjusterAdjustsWithAComment() throws Exception {
        UUID claimId = assessedClaim();

        adjust(claimId, "{\"amount\": 50000, \"comment\": \"Room rent above the plan limit\"}", adjuster())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposedAmount").value(50000.00))
                .andExpect(jsonPath("$.finalAmount").value(50000.00))
                .andExpect(jsonPath("$.calculatedAmount").value(56250.00))
                .andExpect(jsonPath("$.adjusted").value(true))
                .andExpect(jsonPath("$.adjustments.length()").value(1))
                .andExpect(jsonPath("$.adjustments[0].from").value(56250.00))
                .andExpect(jsonPath("$.adjustments[0].to").value(50000.00))
                .andExpect(jsonPath("$.adjustments[0].comment").value("Room rent above the plan limit"))
                .andExpect(jsonPath("$.adjustments[0].by.name").value("Rohan Mehta"));

        // Claim Intake was told, so the claim shows the new amount.
        assertThat(CLAIMS.recordedFor(claimId)).singleElement().satisfies(r -> {
            assertThat(r.getProposedAmount()).isEqualTo("50000.00");
            assertThat(r.getComment()).isEqualTo("Room rent above the plan limit");
            assertThat(r.getActor().getRole()).isEqualTo("ADJUSTER");
            assertThat(r.getActor().getId()).isEqualTo(ADJUSTER_ID.toString());
        });

        // ASSESSMENT_ADJUSTED in the shape the Ops Dashboard maps ({before, after} → proposedAmount).
        assertThat(EVENTS.forClaim(claimId)).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("ASSESSMENT_ADJUSTED");
            assertThat(e.service()).isEqualTo("assessment");
            assertThat(e.actor()).isEqualTo(new ClaimEvent.Actor(ADJUSTER_ID.toString(), "ADJUSTER", "Rohan Mehta"));
            assertThat(e.amount()).isEqualByComparingTo("50000");
            assertThat((BigDecimal) e.change().get("before")).isEqualByComparingTo("56250");
            assertThat((BigDecimal) e.change().get("after")).isEqualByComparingTo("50000");
            assertThat(e.comment()).isEqualTo("Room rent above the plan limit");
            assertThat(e.policyNo()).startsWith("POL-T-");
            assertThat(e.toStatus()).as("only Claim Intake sets toStatus").isNull();
        });
    }

    @Test
    void anAmountAboveTheClaimedAmountIs400() throws Exception {
        UUID claimId = assessedClaim();

        adjust(claimId, "{\"amount\": 67500.01, \"comment\": \"More than was claimed here\"}", adjuster())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("The amount cannot exceed the claimed amount")));
        assertThat(repository.findByClaimId(claimId).orElseThrow().isAdjusted()).isFalse();
        assertThat(CLAIMS.recordedFor(claimId)).isEmpty();

        // The claimed amount itself is allowed.
        adjust(claimId, "{\"amount\": 67500, \"comment\": \"Pay the full claimed amount\"}", adjuster())
                .andExpect(status().isOk());
    }

    @Test
    void zeroNegativeOrMissingAmountsAndShortCommentsAre400() throws Exception {
        UUID claimId = assessedClaim();

        adjust(claimId, "{\"amount\": 0, \"comment\": \"Nothing payable at all\"}", adjuster()).andExpect(status().isBadRequest());
        adjust(claimId, "{\"amount\": -5, \"comment\": \"Nothing payable at all\"}", adjuster()).andExpect(status().isBadRequest());
        adjust(claimId, "{\"comment\": \"Nothing payable at all\"}", adjuster()).andExpect(status().isBadRequest());
        adjust(claimId, "{\"amount\": 100.123, \"comment\": \"Too many decimals here\"}", adjuster()).andExpect(status().isBadRequest());
        adjust(claimId, "{\"amount\": 1000, \"comment\": \"too short\"}", adjuster()).andExpect(status().isBadRequest());
        adjust(claimId, "{\"amount\": 1000, \"comment\": \"   short      \"}", adjuster()).andExpect(status().isBadRequest());
        assertThat(repository.findByClaimId(claimId).orElseThrow().isAdjusted()).isFalse();
    }

    @Test
    void onlyAdjustersMayAdjust() throws Exception {
        UUID claimId = assessedClaim();

        adjust(claimId, "{\"amount\": 1000, \"comment\": \"I would like more please\"}", patient1())
                .andExpect(status().isForbidden());
    }

    @Test
    void adjustmentsStopOnceADecisionIsMade() throws Exception {
        UUID claimId = assessedClaim();
        CLAIMS.status(claimId, ClaimStatus.APPROVED);

        adjust(claimId, "{\"amount\": 1000, \"comment\": \"Too late to change this\"}", adjuster())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The amount can only be changed before a decision is made."));
    }

    @Test
    void anUnassessedClaimCannotBeAdjusted() throws Exception {
        adjust(UUID.randomUUID(), "{\"amount\": 1000, \"comment\": \"Not assessed yet here\"}", adjuster())
                .andExpect(status().isConflict());
    }

    @Test
    void ifClaimIntakeRefusesOrIsDownNothingIsSaved() throws Exception {
        UUID claimId = assessedClaim();
        try {
            CLAIMS.failRecordWith(Status.UNAVAILABLE);
            adjust(claimId, "{\"amount\": 50000, \"comment\": \"Room rent above the plan limit\"}", adjuster())
                    .andExpect(status().isServiceUnavailable());

            CLAIMS.failRecordWith(Status.FAILED_PRECONDITION.withDescription("The claim cannot be changed now."));
            adjust(claimId, "{\"amount\": 50000, \"comment\": \"Room rent above the plan limit\"}", adjuster())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("The claim cannot be changed now."));
        } finally {
            CLAIMS.failRecordWith(null);
        }

        var stored = repository.findByClaimId(claimId).orElseThrow();
        assertThat(stored.isAdjusted()).isFalse();
        assertThat(stored.getFinalAmount()).isEqualByComparingTo("56250.00");
        assertThat(stored.getAdjustments()).isEmpty();
        assertThat(EVENTS.forClaim(claimId)).isEmpty();
        assertThat(CLAIMS.recordedFor(claimId)).extracting(RecordAssessmentRequest::getClaimId).isEmpty();
    }

    @Test
    void healthAndMetricsAreOpen() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
