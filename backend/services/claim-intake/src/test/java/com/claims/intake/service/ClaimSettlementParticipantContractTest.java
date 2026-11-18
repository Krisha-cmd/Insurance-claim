package com.claims.intake.service;

import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.domain.ClaimStatusHistory;
import com.claims.intake.domain.SettlementParticipation;
import com.claims.intake.domain.Actor;
import com.claims.intake.repository.ClaimRepository;
import com.claims.intake.repository.ClaimStatusHistoryRepository;
import com.claims.intake.domain.ClaimStateMachine;
import com.claims.intake.support.IntakeTest;
import com.claims.intake.support.IntakeTestConfig;
import com.claims.participant.ThreePhaseParticipant;
import com.claims.participant.contract.ThreePhaseParticipantContract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared 3PC participant cases (participant test-jar) against the real ClaimSettlementParticipant + Postgres. */
@IntakeTest
class ClaimSettlementParticipantContractTest extends ThreePhaseParticipantContract<SettlementParticipation> {

    @Autowired ClaimSettlementParticipant participant;
    @Autowired ClaimRepository claims;
    @Autowired ClaimStatusHistoryRepository history;
    @Autowired ClaimStateMachine stateMachine;
    @Autowired TransactionTemplate tx;

    private final Map<UUID, ClaimStatus> initial = new ConcurrentHashMap<>();

    @Override protected ThreePhaseParticipant<SettlementParticipation> participant() { return participant; }
    @Override protected void advanceClock(Duration d) { IntakeTestConfig.CLOCK.advance(d); }
    @Override protected Duration prepareTimeout() { return Duration.ofMinutes(5); }
    @Override protected Duration precommitTimeout() { return Duration.ofMinutes(2); }

    private UUID claimIn(ClaimStatus target) {
        List<ClaimStatus> path = List.of(ClaimStatus.DOCS_PENDING, ClaimStatus.DOCS_VERIFIED, ClaimStatus.UNDER_REVIEW, ClaimStatus.APPROVED);
        Claim c = tx.execute(s -> {
            Claim n = Claim.submit("CLM-K-" + UUID.randomUUID().toString().substring(0, 8), "POL-TEST", UUID.randomUUID(),
                    "Test Member", "SELF", UUID.randomUUID(), "Test Holder", UUID.randomUUID(), "Test Hospital",
                    LocalDate.now().minusDays(3), LocalDate.now().minusDays(1), "Test", "Hospitalisation",
                    new BigDecimal("5000.00"), IntakeTestConfig.CLOCK.instant());
            claims.save(n);
            stateMachine.initial(n, Actor.intake());
            for (ClaimStatus step : path) {
                if (n.getStatus() == target) break;
                stateMachine.transition(n, step, Actor.intake(), "test setup");
            }
            return n;
        });
        initial.put(c.getId(), target);
        return c.getId();
    }

    @Override protected UUID committableClaim() { return claimIn(ClaimStatus.APPROVED); }
    @Override protected UUID uncommittableClaim() { return claimIn(ClaimStatus.UNDER_REVIEW); }

    private Claim claim(UUID id) { return claims.findById(id).orElseThrow(); }

    @Override protected void assertPrepared(UUID id) {
        assertThat(claim(id).getStatus()).isEqualTo(ClaimStatus.SETTLING);
        assertThat(claim(id).getApprovedAmount()).isNull();
    }

    @Override protected void assertCommitted(UUID id) {
        assertThat(claim(id).getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(claim(id).getApprovedAmount()).isEqualByComparingTo("1000.00");
        assertThat(history.findByClaimIdOrderByIdAsc(id).stream().map(ClaimStatusHistory::getToStatus)
                .filter(s -> s == ClaimStatus.SETTLED)).as("settled exactly once").hasSize(1);
    }

    @Override protected void assertReleased(UUID id) {
        assertThat(claim(id).getStatus()).isEqualTo(ClaimStatus.SETTLEMENT_FAILED);
        assertThat(claim(id).getApprovedAmount()).isNull();
    }

    @Override protected void assertUntouched(UUID id) {
        assertThat(claim(id).getStatus()).isEqualTo(initial.get(id));
    }
}
