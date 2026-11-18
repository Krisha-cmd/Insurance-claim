package com.claims.eligibility.api;

import com.claims.eligibility.rules.EligibilityRule;
import com.claims.eligibility.service.EligibilityEngine;
import com.claims.eligibility.support.EligibilityTest;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.RuleResult;
import com.claims.proto.eligibility.v1.CheckEligibilityReply;
import com.claims.proto.eligibility.v1.CheckEligibilityRequest;
import com.claims.proto.eligibility.v1.EligibilityServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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

import java.util.UUID;

import static com.claims.eligibility.support.EligibilityTestConfig.CLAIMS;
import static com.claims.eligibility.support.EligibilityTestConfig.EVENTS;
import static com.claims.eligibility.support.EligibilityTestConfig.POLICY;
import static com.claims.eligibility.support.FakeClaimService.PATIENT1_HOLDER;
import static com.claims.eligibility.support.FakeClaimService.PATIENT1_USER;
import static com.claims.eligibility.support.FakePolicyService.ACTIVE;
import static com.claims.eligibility.support.FakePolicyService.ASHA_00017;
import static com.claims.eligibility.support.FakePolicyService.ASHA_00018;
import static com.claims.eligibility.support.FakePolicyService.LAPSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** gRPC Check (Claim Intake's workflow) and ENDPOINTS.eligibility, against in-process Policy and Claim Intake. */
@EligibilityTest
class EligibilityIntegrationTest {

    static final UUID ADJUSTER_ID = UUID.fromString("997bacd0-5b29-4554-b398-52e9f89bfa6c");
    static final String VALID = "{\"eligible\": true, \"reason\": \"Documents reviewed manually\", \"comment\": \"Admission was within cover, verified by phone\"}";

    static ManagedChannel channel;
    static ManagedChannel intakeChannel;
    static EligibilityServiceGrpc.EligibilityServiceBlockingStub grpc;

    @Autowired MockMvc mvc;
    @Autowired EligibilityEngine engine;

    @BeforeAll
    static void connect() {
        channel = InProcessChannelBuilder.forName("eligibility-under-test").build();
        grpc = EligibilityServiceGrpc.newBlockingStub(channel);
        intakeChannel = InProcessChannelBuilder.forName("claim-intake").build();
    }

    @AfterAll
    static void close() {
        channel.shutdownNow();
        intakeChannel.shutdownNow();
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

    /** A claim on the lapsed POL-2026-00018, checked (ineligible) and UNDER_REVIEW. */
    private static UUID lapsedClaimUnderReview() {
        return CLAIMS.claim(LAPSED, ASHA_00018, "2026-03-10", "Hospitalisation", ClaimStatus.UNDER_REVIEW, false);
    }

    private ResultActions override(UUID claimId, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(put("/api/eligibility/{id}/override", claimId).with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ------------------------------------------------------------------ gRPC Check

    @Test
    void springRunsTheSixRuleBeansInTheirOrder() {
        assertThat(engine.rules()).extracting(EligibilityRule::name).containsExactly("POLICY_ACTIVE",
                "WITHIN_POLICY_PERIOD", "MEMBER_COVERED", "TREATMENT_COVERED", "WAITING_PERIOD", "BALANCE_AVAILABLE");
    }

    @Test
    void checkAnEligibleClaim() {
        UUID id = CLAIMS.claim(ACTIVE, ASHA_00017, "2026-03-10", "Hospitalisation", ClaimStatus.DOCS_VERIFIED, null);
        CheckEligibilityReply reply = grpc.check(CheckEligibilityRequest.newBuilder()
                .setClaim(claimDetails(id)).build());

        assertThat(reply.getEligible()).isTrue();
        assertThat(reply.getRulesList()).hasSize(6).allMatch(RuleResult::getPassed);
        assertThat(reply.getCheckedAt()).isNotBlank();
        assertThat(CLAIMS.recordedFor(id)).as("Check stores nothing: Claim Intake records it").isEmpty();
        assertThat(EVENTS.forClaim(id)).as("Claim Intake emits ELIGIBILITY_CHECKED").isEmpty();
    }

    @Test
    void checkAClaimOnTheExpiredPolicy() {
        UUID id = CLAIMS.claim(LAPSED, ASHA_00018, "2026-03-10", "Hospitalisation", ClaimStatus.DOCS_VERIFIED, null);
        CheckEligibilityReply reply = grpc.check(CheckEligibilityRequest.newBuilder().setClaim(claimDetails(id)).build());

        assertThat(reply.getEligible()).isFalse();
        assertThat(reply.getRulesList()).hasSize(6);
        assertThat(reply.getRulesList()).filteredOn(r -> !r.getPassed()).extracting(RuleResult::getRule)
                .containsExactly("POLICY_ACTIVE", "WITHIN_POLICY_PERIOD");
        assertThat(reply.getRules(0).getLabel()).isEqualTo("Policy is active");
        assertThat(reply.getRules(0).getDetail()).isEqualTo("Policy POL-2026-00018 is LAPSED");
    }

    @Test
    void checkErrorsMapToGrpcCodes() {
        UUID unknownPolicy = CLAIMS.claim("POL-NOPE", ASHA_00017, "2026-03-10", "Surgery", ClaimStatus.DOCS_VERIFIED, null);
        assertThatThrownBy(() -> grpc.check(CheckEligibilityRequest.newBuilder().setClaim(claimDetails(unknownPolicy)).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));

        UUID id = CLAIMS.claim(ACTIVE, ASHA_00017, "2026-03-10", "Surgery", ClaimStatus.DOCS_VERIFIED, null);
        POLICY.down(true);
        try {
            assertThatThrownBy(() -> grpc.check(CheckEligibilityRequest.newBuilder().setClaim(claimDetails(id)).build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
        } finally {
            POLICY.down(false);
        }
    }

    /** What Claim Intake sends in Check: its ClaimDetails (read here through the fake's GetClaim). */
    private static com.claims.proto.claim.v1.ClaimDetails claimDetails(UUID id) {
        var stub = com.claims.proto.claim.v1.ClaimServiceGrpc.newBlockingStub(intakeChannel);
        return stub.getClaim(com.claims.proto.claim.v1.GetClaimRequest.newBuilder().setClaimId(id.toString()).build());
    }

    // ------------------------------------------------------------------ REST GET

    @Test
    void getReEvaluatesInTheFrontendShape() throws Exception {
        UUID id = lapsedClaimUnderReview();

        mvc.perform(get("/api/eligibility/{id}", id).with(adjuster()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimId").value(id.toString()))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.rulesEligible").value(false))
                .andExpect(jsonPath("$.checks.length()").value(6))
                .andExpect(jsonPath("$.checks[0].rule").value("POLICY_ACTIVE"))
                .andExpect(jsonPath("$.checks[0].passed").value(false))
                .andExpect(jsonPath("$.checks[0].detail").value("Policy POL-2026-00018 is LAPSED"))
                .andExpect(jsonPath("$.checks[2].rule").value("MEMBER_COVERED"))
                .andExpect(jsonPath("$.checks[2].passed").value(true))
                .andExpect(jsonPath("$.evaluatedAt").isString())
                .andExpect(jsonPath("$.overridden").value(false))
                .andExpect(jsonPath("$.override").isEmpty());
    }

    @Test
    void theOwnerMayReadButNotAnotherPolicyholder() throws Exception {
        UUID id = lapsedClaimUnderReview();

        mvc.perform(get("/api/eligibility/{id}", id).with(patient1())).andExpect(status().isOk());
        mvc.perform(get("/api/eligibility/{id}", id).with(otherPolicyholder())).andExpect(status().isForbidden());
        mvc.perform(get("/api/eligibility/{id}", id)).andExpect(status().isUnauthorized());
    }

    @Test
    void notCheckedYetIs404AndAnUnknownClaimToo() throws Exception {
        UUID notChecked = CLAIMS.claim(ACTIVE, ASHA_00017, "2026-03-10", "Surgery", ClaimStatus.DOCS_PENDING, null);

        mvc.perform(get("/api/eligibility/{id}", notChecked).with(adjuster()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Eligibility has not been checked yet."));
        mvc.perform(get("/api/eligibility/{id}", UUID.randomUUID()).with(adjuster())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ REST override

    @Test
    void anOverrideWithoutACommentIs400() throws Exception {
        UUID id = lapsedClaimUnderReview();

        override(id, "{\"eligible\": true, \"reason\": \"Documents reviewed manually\"}", adjuster())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("comment")));
        override(id, "{\"eligible\": true, \"reason\": \"Documents reviewed manually\", \"comment\": \"too short\"}", adjuster())
                .andExpect(status().isBadRequest());
        override(id, "{\"eligible\": true, \"reason\": \"Documents reviewed manually\", \"comment\": \"   short     \"}", adjuster())
                .andExpect(status().isBadRequest());
        override(id, "{\"eligible\": true, \"comment\": \"Admission was within cover, verified\"}", adjuster())
                .andExpect(status().isBadRequest());
        override(id, "{\"reason\": \"Documents reviewed manually\", \"comment\": \"Admission was within cover, verified\"}", adjuster())
                .andExpect(status().isBadRequest());

        assertThat(CLAIMS.recordedFor(id)).isEmpty();
        assertThat(EVENTS.forClaim(id)).isEmpty();
    }

    @Test
    void aValidOverrideIsRecordedInIntakeAndAudited() throws Exception {
        UUID id = lapsedClaimUnderReview();

        override(id, VALID, adjuster())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.rulesEligible").value(false))
                .andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.override.eligible").value(true))
                .andExpect(jsonPath("$.override.reason").value("Documents reviewed manually"))
                .andExpect(jsonPath("$.override.comment").value("Admission was within cover, verified by phone"))
                .andExpect(jsonPath("$.override.by.name").value("Rohan Mehta"))
                .andExpect(jsonPath("$.checks.length()").value(6));

        assertThat(CLAIMS.recordedFor(id)).singleElement().satisfies(r -> {
            assertThat(r.getOverridden()).isTrue();
            assertThat(r.getEligible()).isTrue();
            assertThat(r.getOverrideReason()).isEqualTo("Documents reviewed manually");
            assertThat(r.getOverrideComment()).isEqualTo("Admission was within cover, verified by phone");
            assertThat(r.getActor().getId()).isEqualTo(ADJUSTER_ID.toString());
            assertThat(r.getActor().getRole()).isEqualTo("ADJUSTER");
            assertThat(r.getRulesList()).hasSize(6);
        });

        assertThat(EVENTS.forClaim(id)).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("ELIGIBILITY_OVERRIDDEN");
            assertThat(e.service()).isEqualTo("eligibility");
            assertThat(e.actor().name()).isEqualTo("Rohan Mehta");
            assertThat(e.change()).containsEntry("before", false).containsEntry("after", true)
                    .containsEntry("reason", "Documents reviewed manually");
            assertThat(e.comment()).isEqualTo("Admission was within cover, verified by phone");
            assertThat(e.policyNo()).isEqualTo(LAPSED);
            assertThat(e.toStatus()).as("only Claim Intake sets toStatus").isNull();
        });

        // GET now merges the override recorded in Claim Intake, while the checks are re-evaluated.
        mvc.perform(get("/api/eligibility/{id}", id).with(adjuster()))
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.rulesEligible").value(false))
                .andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.override.reason").value("Documents reviewed manually"))
                .andExpect(jsonPath("$.override.by.name").value("Rohan Mehta"))
                .andExpect(jsonPath("$.override.by.role").value("ADJUSTER"))
                .andExpect(jsonPath("$.override.at").isString());
    }

    @Test
    void onlyAdjustersMayOverride() throws Exception {
        override(lapsedClaimUnderReview(), VALID, patient1()).andExpect(status().isForbidden());
    }

    @Test
    void overridesStopOnceADecisionIsMade() throws Exception {
        UUID id = lapsedClaimUnderReview();
        CLAIMS.status(id, ClaimStatus.APPROVED);

        override(id, VALID, adjuster())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Eligibility can only be changed before a decision is made."));
    }

    @Test
    void anUncheckedClaimCannotBeOverridden() throws Exception {
        UUID id = CLAIMS.claim(ACTIVE, ASHA_00017, "2026-03-10", "Surgery", ClaimStatus.DOCS_VERIFIED, null);
        override(id, VALID, adjuster()).andExpect(status().isConflict());
    }

    @Test
    void ifClaimIntakeIsDownNothingIsAudited() throws Exception {
        UUID id = lapsedClaimUnderReview();
        CLAIMS.failRecordWith(Status.UNAVAILABLE);
        try {
            override(id, VALID, adjuster()).andExpect(status().isServiceUnavailable());
        } finally {
            CLAIMS.failRecordWith(null);
        }
        assertThat(EVENTS.forClaim(id)).isEmpty();
    }
}
