package com.claims.policy.service;

import com.claims.participant.ThreePhaseParticipant;
import com.claims.participant.contract.ThreePhaseParticipantContract;
import com.claims.policy.domain.LimitReservation;
import com.claims.policy.support.PolicyTest;
import com.claims.policy.support.PolicyTestConfig;
import com.claims.policy.support.TestPolicies;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared 3PC participant cases (participant test-jar) against LimitReservationParticipant + Postgres. */
@PolicyTest
class LimitReservationParticipantContractTest extends ThreePhaseParticipantContract<LimitReservation> {

    @Autowired LimitReservationParticipant participant;
    @Autowired JdbcTemplate jdbc;

    private final Map<UUID, String> policyOf = new ConcurrentHashMap<>();

    @Override protected ThreePhaseParticipant<LimitReservation> participant() { return participant; }
    @Override protected void advanceClock(Duration d) { PolicyTestConfig.CLOCK.advance(d); }
    @Override protected Duration prepareTimeout() { return Duration.ofMinutes(5); }
    @Override protected Duration precommitTimeout() { return Duration.ofMinutes(2); }

    private UUID claimOn(String sumInsured, String status) {
        String policyNo = TestPolicies.create(jdbc, sumInsured, status);
        UUID claimId = PolicyTestConfig.CLAIMS.claimOn(policyNo);
        policyOf.put(claimId, policyNo);
        return claimId;
    }

    @Override protected UUID committableClaim() { return claimOn("5000.00", "ACTIVE"); }
    /** Not enough left: 500 insured, 1000 asked. */
    @Override protected UUID uncommittableClaim() { return claimOn("500.00", "ACTIVE"); }

    private TestPolicies.Balance balance(UUID claimId) { return TestPolicies.balance(jdbc, policyOf.get(claimId)); }

    @Override protected void assertPrepared(UUID c) {
        assertThat(balance(c).reserved()).isEqualByComparingTo("1000.00");
        assertThat(balance(c).utilised()).isEqualByComparingTo("0");
    }

    @Override protected void assertCommitted(UUID c) {
        assertThat(balance(c).reserved()).isEqualByComparingTo("0");
        assertThat(balance(c).utilised()).as("paid exactly once").isEqualByComparingTo("1000.00");
    }

    @Override protected void assertReleased(UUID c) {
        assertThat(balance(c).reserved()).isEqualByComparingTo("0");
        assertThat(balance(c).utilised()).isEqualByComparingTo("0");
    }

    @Override protected void assertUntouched(UUID c) { assertReleased(c); }
}
