package com.claims.policy.service;

import com.claims.participant.ParticipantTxState;
import com.claims.participant.VoteResult;
import com.claims.policy.repository.LimitReservationRepository;
import com.claims.policy.support.PolicyTest;
import com.claims.policy.support.PolicyTestConfig;
import com.claims.policy.support.TestPolicies;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@PolicyTest
class ReservationIntegrationTest {

    @Autowired LimitReservationParticipant participant;
    @Autowired LimitReservationRepository reservations;
    @Autowired JdbcTemplate jdbc;

    /** Two settlements of 60,000 against 100,000 left, started at the same instant: exactly one may reserve. */
    @RepeatedTest(5)
    void concurrentCanCommitsThatTogetherExceedTheBalance_exactlyOneVotesYes() throws Exception {
        String policyNo = TestPolicies.create(jdbc, "100000.00", "ACTIVE");
        UUID claimA = PolicyTestConfig.CLAIMS.claimOn(policyNo);
        UUID claimB = PolicyTestConfig.CLAIMS.claimOn(policyNo);
        BigDecimal amount = new BigDecimal("60000.00");

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<VoteResult>> votes = new ArrayList<>();
            for (UUID claim : List.of(claimA, claimB)) {
                votes.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return participant.canCommit(UUID.randomUUID(), claim, amount, 1);
                }, pool));
            }
            List<VoteResult> results = votes.stream().map(CompletableFuture::join).toList();

            assertThat(results.stream().filter(VoteResult::yes)).hasSize(1);
            VoteResult no = results.stream().filter(v -> !v.yes()).findFirst().orElseThrow();
            assertThat(no.reason()).isEqualTo("Remaining sum insured ₹40,000 is less than ₹60,000");
            assertThat(TestPolicies.balance(jdbc, policyNo).reserved()).isEqualByComparingTo("60000.00");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void commitMovesReservedToUtilised_abortReleases() {
        String policyNo = TestPolicies.create(jdbc, "100000.00", "ACTIVE");
        UUID paid = UUID.randomUUID(), dropped = UUID.randomUUID();

        assertThat(participant.canCommit(paid, PolicyTestConfig.CLAIMS.claimOn(policyNo), new BigDecimal("30000"), 1).yes()).isTrue();
        assertThat(participant.canCommit(dropped, PolicyTestConfig.CLAIMS.claimOn(policyNo), new BigDecimal("20000"), 1).yes()).isTrue();
        assertThat(TestPolicies.balance(jdbc, policyNo).reserved()).isEqualByComparingTo("50000");

        participant.preCommit(paid);
        participant.doCommit(paid);
        participant.abort(dropped);

        TestPolicies.Balance b = TestPolicies.balance(jdbc, policyNo);
        assertThat(b.utilised()).isEqualByComparingTo("30000");
        assertThat(b.reserved()).isEqualByComparingTo("0");
        assertThat(reservations.findById(paid).orElseThrow().getPolicyId()).isNotNull();
        assertThat(participant.stateOf(dropped)).contains(ParticipantTxState.ABORTED);
    }

    @Test
    void aLapsedPolicyOrAnUnknownClaimVotesNo() {
        String lapsed = TestPolicies.create(jdbc, "100000.00", "LAPSED");
        VoteResult onLapsed = participant.canCommit(UUID.randomUUID(), PolicyTestConfig.CLAIMS.claimOn(lapsed), new BigDecimal("100"), 1);
        assertThat(onLapsed.yes()).isFalse();
        assertThat(onLapsed.reason()).isEqualTo("Policy " + lapsed + " is LAPSED");

        UUID unknown = UUID.randomUUID();
        VoteResult onUnknown = participant.canCommit(UUID.randomUUID(), unknown, new BigDecimal("100"), 1);
        assertThat(onUnknown.reason()).isEqualTo("Claim " + unknown + " not found");
    }
}
