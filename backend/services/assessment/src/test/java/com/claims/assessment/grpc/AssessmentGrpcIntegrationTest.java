package com.claims.assessment.grpc;

import com.claims.assessment.api.CurrentUser;
import com.claims.assessment.api.dto.AdjustRequest;
import com.claims.assessment.repository.AssessmentRepository;
import com.claims.assessment.service.AssessmentService;
import com.claims.assessment.support.AssessmentTest;
import com.claims.proto.assessment.v1.AssessReply;
import com.claims.proto.assessment.v1.AssessmentLine;
import com.claims.proto.assessment.v1.AssessmentServiceGrpc;
import com.claims.proto.assessment.v1.FinalAmountReply;
import com.claims.proto.assessment.v1.GetFinalAmountRequest;
import com.claims.proto.claim.v1.ClaimStatus;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static com.claims.assessment.support.AssessmentTestConfig.CLAIMS;
import static com.claims.assessment.support.AssessmentTestConfig.POLICY;
import static com.claims.assessment.support.TestClaims.assess;
import static com.claims.assessment.support.TestClaims.claim;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AssessmentService over real (in-process) gRPC, as Claim Intake and Approval call it. */
@AssessmentTest
class AssessmentGrpcIntegrationTest {

    static ManagedChannel channel;
    static AssessmentServiceGrpc.AssessmentServiceBlockingStub stub;

    @Autowired AssessmentRepository repository;
    @Autowired AssessmentService service;
    @Autowired MeterRegistry meters;

    @BeforeAll
    static void connect() {
        channel = InProcessChannelBuilder.forName("assessment-under-test").build();
        stub = AssessmentServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    static void close() {
        channel.shutdownNow();
    }

    private static final CurrentUser ADJUSTER =
            new CurrentUser(UUID.fromString("997bacd0-5b29-4554-b398-52e9f89bfa6c"), "ADJUSTER", "Rohan Mehta", null);

    @Test
    void assessReturnsTheProposedAmountAndTheBreakdown() {
        String policy = POLICY.newPolicy();
        AssessReply reply = stub.assess(assess(claim(UUID.randomUUID(), policy, "Day care procedure", "60000.00")));

        assertThat(reply.getProposedAmount()).isEqualTo("40500.00");
        assertThat(reply.getCurrency()).isEqualTo("INR");
        assertThat(reply.getLinesList()).extracting(AssessmentLine::getLabel).containsExactly(
                "Claimed amount", "Capped at the Day care procedure sub-limit of 50000.00", "Deductible",
                "After deductible", "Co-pay (10%)", "Proposed amount");
        assertThat(reply.getLinesList()).extracting(AssessmentLine::getAmount).containsExactly(
                "60000.00", "50000.00", "-5000.00", "45000.00", "-4500.00", "40500.00");
    }

    @Test
    void assessingTwiceKeepsOneRow() {
        String policy = POLICY.newPolicy();
        UUID claimId = UUID.randomUUID();

        AssessReply first = stub.assess(assess(claim(claimId, policy, "Hospitalisation", "67500.00")));
        AssessReply second = stub.assess(assess(claim(claimId, policy, "Hospitalisation", "67500.00")));

        assertThat(repository.countByClaimId(claimId)).isEqualTo(1);
        assertThat(first.getProposedAmount()).isEqualTo("56250.00");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void reassessingBeforeAnAdjustmentPicksUpANewBalance() {
        String policy = POLICY.newPolicy();
        UUID claimId = UUID.randomUUID();
        stub.assess(assess(claim(claimId, policy, "Hospitalisation", "67500.00")));

        POLICY.remaining(policy, "30000.00");
        AssessReply again = stub.assess(assess(claim(claimId, policy, "Hospitalisation", "67500.00")));

        assertThat(again.getProposedAmount()).isEqualTo("30000.00");
        assertThat(repository.countByClaimId(claimId)).isEqualTo(1);
    }

    @Test
    void afterAnAdjustmentAssessDoesNotOverwriteTheFinalAmount() {
        String policy = POLICY.newPolicy();
        UUID claimId = UUID.randomUUID();
        stub.assess(assess(claim(claimId, policy, "Hospitalisation", "67500.00")));
        CLAIMS.status(claimId, ClaimStatus.UNDER_REVIEW);
        service.adjust(claimId, new AdjustRequest(new BigDecimal("50000"), "Room rent above the plan limit"), ADJUSTER);

        POLICY.remaining(policy, "1000.00"); // would change a fresh calculation
        int policyCalls = POLICY.calls.get();
        AssessReply again = stub.assess(assess(claim(claimId, policy, "Hospitalisation", "67500.00")));

        assertThat(again.getProposedAmount()).isEqualTo("50000.00");
        assertThat(POLICY.calls.get()).as("an adjusted assessment needs no Policy lookup").isEqualTo(policyCalls);
        var stored = repository.findByClaimId(claimId).orElseThrow();
        assertThat(stored.getFinalAmount()).isEqualByComparingTo("50000.00");
        assertThat(stored.getProposedAmount()).isEqualByComparingTo("56250.00");
        assertThat(stored.isAdjusted()).isTrue();
    }

    @Test
    void getFinalAmountIsTheAdjustedAmountElseTheProposedOne() {
        String policy = POLICY.newPolicy();
        UUID claimId = UUID.randomUUID();
        stub.assess(assess(claim(claimId, policy, "Hospitalisation", "67500.00")));
        GetFinalAmountRequest req = GetFinalAmountRequest.newBuilder().setClaimId(claimId.toString()).build();

        FinalAmountReply before = stub.getFinalAmount(req);
        assertThat(before.getAmount()).isEqualTo("56250.00");
        assertThat(before.getCurrency()).isEqualTo("INR");
        assertThat(before.getClaimId()).isEqualTo(claimId.toString());

        CLAIMS.status(claimId, ClaimStatus.ASSESSED);
        service.adjust(claimId, new AdjustRequest(new BigDecimal("60000.00"), "Approved room upgrade, see notes"), ADJUSTER);
        assertThat(stub.getFinalAmount(req).getAmount()).isEqualTo("60000.00");
    }

    @Test
    void getFinalAmountForAnUnassessedClaimIsNotFound() {
        assertThatThrownBy(() -> stub.getFinalAmount(
                GetFinalAmountRequest.newBuilder().setClaimId(UUID.randomUUID().toString()).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void badInputAndPolicyProblemsMapToGrpcCodes() {
        String policy = POLICY.newPolicy();
        assertThatThrownBy(() -> stub.assess(assess(claim(UUID.randomUUID(), policy, "Surgery", "a lot"))))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        assertThatThrownBy(() -> stub.assess(assess(claim(UUID.randomUUID(), "POL-UNKNOWN", "Surgery", "1000"))))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));

        POLICY.down(true);
        try {
            assertThatThrownBy(() -> stub.assess(assess(claim(UUID.randomUUID(), policy, "Surgery", "1000"))))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
        } finally {
            POLICY.down(false);
        }
    }

    @Test
    void theCalculationIsTimed() {
        long before = meters.get("claims.assessment.calculate").timer().count();
        stub.assess(assess(claim(UUID.randomUUID(), POLICY.newPolicy(), "Surgery", "25000.00")));

        assertThat(meters.get("claims.assessment.calculate").timer().count()).isEqualTo(before + 1);
    }
}
