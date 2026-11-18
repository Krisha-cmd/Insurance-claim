package com.claims.policy.grpc;

import com.claims.policy.support.PolicyTest;
import com.claims.policy.support.PolicyTestConfig;
import com.claims.policy.support.TestPolicies;
import com.claims.proto.policy.v1.PolicyNoRequest;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import com.claims.proto.policy.v1.ValidateMemberRequest;
import com.claims.proto.settlement.v1.CanCommitRequest;
import com.claims.proto.settlement.v1.ParticipantState;
import com.claims.proto.settlement.v1.SettlementParticipantGrpc;
import com.claims.proto.settlement.v1.TxRequest;
import com.claims.proto.settlement.v1.Vote;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PolicyService and SettlementParticipant through the real gRPC server (in-process transport). */
@PolicyTest
class PolicyGrpcIntegrationTest {

    static final String PATIENT1_HOLDER = "a2b56321-c8b8-4159-93d6-3cf613b3c556";
    static final String SELF = "567dacf4-a6e5-461f-b094-8ded155a5a7e";

    @Value("${claims.grpc.server.in-process-name}") String serverName;
    @Autowired JdbcTemplate jdbc;
    ManagedChannel channel;

    @BeforeEach void open() { channel = InProcessChannelBuilder.forName(serverName).build(); }
    @AfterEach void close() { channel.shutdownNow(); }

    private ValidateMemberRequest validate(String policyNo, String member, String holder) {
        return ValidateMemberRequest.newBuilder().setPolicyNo(policyNo).setMemberId(member).setPolicyholderId(holder).build();
    }

    @Test
    void validateMember() {
        var stub = PolicyServiceGrpc.newBlockingStub(channel);

        var ok = stub.validateMember(validate("POL-2026-00017", SELF, PATIENT1_HOLDER));
        assertThat(ok.getValid()).isTrue();
        assertThat(ok.getMemberName()).isEqualTo("Asha Verma");
        assertThat(ok.getRelation()).isEqualTo("SELF");
        assertThat(ok.getPolicyholderName()).isEqualTo("Asha Verma");

        assertThat(stub.validateMember(validate("POL-2026-00017", UUID.randomUUID().toString(), PATIENT1_HOLDER)).getReason())
                .isEqualTo("The patient is not insured under this policy.");
        assertThat(stub.validateMember(validate("POL-2026-00017", SELF, UUID.randomUUID().toString())).getReason())
                .isEqualTo("This policy belongs to someone else.");
        var lapsed = stub.validateMember(validate("POL-2026-00018", "6ee0f2b2-1c47-43a7-b6f6-3af17479a96f", PATIENT1_HOLDER));
        assertThat(lapsed.getValid()).as("a lapsed policy is Eligibility's call").isTrue();
        assertThat(lapsed.getPolicyStatus()).isEqualTo("LAPSED");
        assertThatThrownBy(() -> stub.validateMember(validate("POL-NOPE", SELF, PATIENT1_HOLDER)))
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void getPolicyCoverageAndBalance() {
        var stub = PolicyServiceGrpc.newBlockingStub(channel);
        PolicyNoRequest req = PolicyNoRequest.newBuilder().setPolicyNo("POL-2026-00017").build();

        var policy = stub.getPolicy(req);
        assertThat(policy.getMembersCount()).isEqualTo(2);
        assertThat(policy.getStatus()).isEqualTo("ACTIVE");
        assertThat(policy.getSumInsured()).isEqualTo("500000.00");

        var coverage = stub.getCoverage(req);
        assertThat(coverage.getDeductible()).isEqualTo("5000.00");
        assertThat(coverage.getCopayPct()).isEqualTo("10.00");
        assertThat(coverage.getRulesList()).anySatisfy(r -> {
            assertThat(r.getTreatmentCategory()).isEqualTo("MATERNITY");
            assertThat(r.getWaitingPeriodDays()).isEqualTo(270);
            assertThat(r.getSubLimit()).isEqualTo("75000.00");
        });
        assertThat(coverage.getRulesList()).anySatisfy(r -> {
            assertThat(r.getTreatmentCategory()).isEqualTo("COSMETIC");
            assertThat(r.getCovered()).isFalse();
            assertThat(r.getSubLimit()).isEmpty();
        });

        var balance = stub.getBalance(req);
        assertThat(balance.getSumInsured()).isEqualTo("500000.00");
        assertThat(balance.getRemaining()).isNotEmpty();
    }

    @Test
    void aSettlementOverGrpc() {
        String policyNo = TestPolicies.create(jdbc, "100000.00", "ACTIVE");
        UUID claimId = PolicyTestConfig.CLAIMS.claimOn(policyNo);
        var stub = SettlementParticipantGrpc.newBlockingStub(channel);
        String txId = UUID.randomUUID().toString();

        var vote = stub.canCommit(CanCommitRequest.newBuilder().setTxId(txId).setClaimId(claimId.toString())
                .setAmount("25000.00").setCurrency("INR").setAttempt(1).build());
        assertThat(vote.getVote()).isEqualTo(Vote.VOTE_YES);

        TxRequest tx = TxRequest.newBuilder().setTxId(txId).build();
        assertThat(stub.preCommit(tx).getState()).isEqualTo(ParticipantState.PRECOMMITTED);
        assertThat(stub.doCommit(tx).getState()).isEqualTo(ParticipantState.COMMITTED);
        assertThat(TestPolicies.balance(jdbc, policyNo).utilised()).isEqualByComparingTo("25000");

        var tooMuch = stub.canCommit(CanCommitRequest.newBuilder().setTxId(UUID.randomUUID().toString())
                .setClaimId(PolicyTestConfig.CLAIMS.claimOn(policyNo).toString()).setAmount("80000").setAttempt(1).build());
        assertThat(tooMuch.getVote()).isEqualTo(Vote.VOTE_NO);
        assertThat(tooMuch.getReason()).isEqualTo("Remaining sum insured ₹75,000 is less than ₹80,000");
    }
}
