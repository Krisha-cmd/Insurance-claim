package com.claims.intake.grpc;

import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.service.ClaimService;
import com.claims.intake.support.IntakeTestBase;
import com.claims.proto.claim.v1.Actor;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.GetClaimRequest;
import com.claims.proto.claim.v1.RecordAssessmentRequest;
import com.claims.proto.claim.v1.RecordEligibilityRequest;
import com.claims.proto.claim.v1.RuleResult;
import com.claims.proto.claim.v1.UpdateStatusReply;
import com.claims.proto.claim.v1.UpdateStatusRequest;
import com.claims.proto.settlement.v1.CanCommitRequest;
import com.claims.proto.settlement.v1.ParticipantState;
import com.claims.proto.settlement.v1.SettlementParticipantGrpc;
import com.claims.proto.settlement.v1.TxRequest;
import com.claims.proto.settlement.v1.Vote;
import com.claims.common.error.ApiException;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ClaimService and SettlementParticipant through Claim Intake's real gRPC server (in-process transport). */
class ClaimGrpcIntegrationTest extends IntakeTestBase {

    @Value("${claims.grpc.server.in-process-name}") String serverName;
    @Autowired ClaimService claimService;

    private ManagedChannel channel;

    private ManagedChannel channel() {
        if (channel == null) channel = InProcessChannelBuilder.forName(serverName).build();
        return channel;
    }

    @AfterEach
    void close() {
        if (channel != null) channel.shutdownNow();
    }

    private UpdateStatusRequest update(Claim c, com.claims.proto.claim.v1.ClaimStatus to) {
        return UpdateStatusRequest.newBuilder().setClaimId(c.getId().toString()).setStatus(to).setReason("Within limits")
                .setActor(Actor.newBuilder().setId(ADJUSTER_ID.toString()).setRole("ADJUSTER").setName("Rohan Mehta"))
                .setIdempotencyKey("decision:" + c.getId()).build();
    }

    @Test
    void anIllegalTransitionIs409_andFailedPreconditionOverGrpc() {
        Claim c = claimIn(ClaimStatus.SUBMITTED, "5000");

        assertThatThrownBy(() -> claimService.updateStatus(c.getId(), ClaimStatus.APPROVED, "x", com.claims.intake.domain.Actor.intake()))
                .isInstanceOf(ApiException.class)
                .hasMessage("A claim cannot move from SUBMITTED to APPROVED.")
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        var stub = ClaimServiceGrpc.newBlockingStub(channel());
        assertThatThrownBy(() -> stub.updateStatus(update(c, com.claims.proto.claim.v1.ClaimStatus.APPROVED)))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> {
                    Status s = ((StatusRuntimeException) e).getStatus();
                    assertThat(s.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
                    assertThat(s.getDescription()).isEqualTo("A claim cannot move from SUBMITTED to APPROVED.");
                });
        assertThat(claims.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
    }

    @Test
    void approvalSetsTheStatus_andRepeatingItIsANoOp() {
        Claim c = claimIn(ClaimStatus.UNDER_REVIEW, "5000");
        var stub = ClaimServiceGrpc.newBlockingStub(channel());

        UpdateStatusReply first = stub.updateStatus(update(c, com.claims.proto.claim.v1.ClaimStatus.APPROVED));
        assertThat(first.getChanged()).isTrue();
        assertThat(first.getPreviousStatus()).isEqualTo(com.claims.proto.claim.v1.ClaimStatus.UNDER_REVIEW);
        UpdateStatusReply again = stub.updateStatus(update(c, com.claims.proto.claim.v1.ClaimStatus.APPROVED));
        assertThat(again.getChanged()).isFalse();
        assertThat(again.getStatus()).isEqualTo(com.claims.proto.claim.v1.ClaimStatus.APPROVED);

        assertThatThrownBy(() -> stub.updateStatus(update(c, com.claims.proto.claim.v1.ClaimStatus.SUBMITTED).toBuilder()
                .setClaimId(UUID.randomUUID().toString()).build()))
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void getClaimAndRecordAssessment() {
        Claim c = claimIn(ClaimStatus.UNDER_REVIEW, "5000");
        var stub = ClaimServiceGrpc.newBlockingStub(channel());

        stub.recordAssessment(RecordAssessmentRequest.newBuilder().setClaimId(c.getId().toString()).setProposedAmount("4200.50")
                .setComment("Capped").setActor(Actor.newBuilder().setId(ADJUSTER_ID.toString()).setRole("ADJUSTER")).build());
        var details = stub.getClaim(GetClaimRequest.newBuilder().setClaimId(c.getId().toString()).build());

        assertThat(details.getProposedAmount()).isEqualTo("4200.50");
        assertThat(details.getClaimedAmount()).isEqualTo("5000.00");
        assertThat(details.getStatus()).isEqualTo(com.claims.proto.claim.v1.ClaimStatus.UNDER_REVIEW);
        assertThat(details.getSubmittedBy()).isEqualTo(c.getSubmittedBy().toString());
    }

    @Test
    void anEligibilityOverrideWithItsReasonComesBackFromGetClaim() {
        Claim c = claimIn(ClaimStatus.UNDER_REVIEW, "5000");
        var stub = ClaimServiceGrpc.newBlockingStub(channel());
        var get = GetClaimRequest.newBuilder().setClaimId(c.getId().toString()).build();
        assertThat(stub.getClaim(get).hasLatestEligibility()).as("nothing recorded yet").isFalse();

        stub.recordEligibility(RecordEligibilityRequest.newBuilder().setClaimId(c.getId().toString())
                .setEligible(true).setOverridden(true)
                .setOverrideReason("Documents reviewed manually").setOverrideComment("Verified the admission by phone")
                .addRules(RuleResult.newBuilder().setRule("POLICY_ACTIVE").setLabel("Policy is active").setPassed(false)
                        .setDetail("Policy POL-2026-00018 is LAPSED"))
                .setActor(Actor.newBuilder().setId(ADJUSTER_ID.toString()).setRole("ADJUSTER").setName("Rohan Mehta"))
                .build());

        var latest = stub.getClaim(get).getLatestEligibility();
        assertThat(latest.getEligible()).isTrue();
        assertThat(latest.getOverridden()).isTrue();
        assertThat(latest.getOverrideReason()).isEqualTo("Documents reviewed manually");
        assertThat(latest.getOverrideComment()).isEqualTo("Verified the admission by phone");
        assertThat(latest.getCheckedBy()).isEqualTo("ADJUSTER:Rohan Mehta");
        assertThat(latest.getCheckedAt()).isNotBlank();
    }

    @Test
    void settlementThroughTheParticipantService() {
        Claim c = claimIn(ClaimStatus.APPROVED, "5000");
        var stub = SettlementParticipantGrpc.newBlockingStub(channel());
        String txId = UUID.randomUUID().toString();

        var vote = stub.canCommit(CanCommitRequest.newBuilder().setTxId(txId).setClaimId(c.getId().toString())
                .setAmount("4500.00").setCurrency("INR").setAttempt(1).build());
        assertThat(vote.getVote()).isEqualTo(Vote.VOTE_YES);
        assertThat(claims.findById(c.getId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.SETTLING);

        TxRequest tx = TxRequest.newBuilder().setTxId(txId).build();
        assertThat(stub.preCommit(tx).getState()).isEqualTo(ParticipantState.PRECOMMITTED);
        assertThat(stub.doCommit(tx).getState()).isEqualTo(ParticipantState.COMMITTED);
        Claim settled = claims.findById(c.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(settled.getApprovedAmount()).isEqualByComparingTo("4500.00");

        assertThatThrownBy(() -> stub.abort(tx))
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
        assertThat(events().forClaim(c.getId()).stream().filter(e -> e.eventType().startsWith("TX_")))
                .allSatisfy(e -> assertThat(e.participant()).isEqualTo("claim-intake"));
    }

    @Test
    void aClaimThatIsNotApprovedVotesNo() {
        Claim c = claimIn(ClaimStatus.UNDER_REVIEW, "5000");
        var vote = SettlementParticipantGrpc.newBlockingStub(channel()).canCommit(CanCommitRequest.newBuilder()
                .setTxId(UUID.randomUUID().toString()).setClaimId(c.getId().toString()).setAmount("100").setAttempt(1).build());
        assertThat(vote.getVote()).isEqualTo(Vote.VOTE_NO);
        assertThat(vote.getReason()).isEqualTo("Claim is UNDER_REVIEW, not APPROVED");
    }
}
