package com.claims.approval.service;

import com.claims.approval.api.dto.DecisionRequest;
import com.claims.approval.api.dto.DecisionResponse;
import com.claims.approval.api.dto.SettlementView;
import com.claims.approval.domain.CoordinatorState;
import com.claims.approval.domain.Decision;
import com.claims.approval.domain.SettlementTx;
import com.claims.approval.domain.SettlementTxParticipant;
import com.claims.approval.domain.TxState;
import com.claims.approval.domain.Vote;
import com.claims.approval.repository.SettlementTxParticipantRepository;
import com.claims.approval.support.FakeParticipant;
import com.claims.approval.support.IntegrationTestBase;
import com.claims.common.error.ApiException;
import com.claims.common.event.ClaimEvent;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.settlement.v1.ParticipantState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** The acceptance criteria, against three in-process fake participants. */
class ThreePhaseCommitIntegrationTest extends IntegrationTestBase {

    @Autowired ApprovalService approvals;
    @Autowired ThreePhaseCommitCoordinator coordinator;
    @Autowired CoordinatorRecovery recovery;
    @Autowired SettlementTxParticipantRepository participantRows;

    private UUID approve(UUID claimId) {
        DecisionResponse res = approvals.decide(claimId,
                new DecisionRequest(Decision.APPROVED, "Bills match the assessment."), ADJUSTER);
        assertThat(res.txId()).isNotNull();
        return res.txId();
    }

    /** Waits until the settlement thread has stopped (finished or "crashed"). */
    private SettlementTx awaitStopped(UUID txId, CoordinatorState expected) {
        await().atMost(Duration.ofSeconds(10)).until(() -> !coordinator.isInFlight(txId));
        SettlementTx t = txs.findById(txId).orElseThrow();
        assertThat(t.getState()).isEqualTo(expected);
        return t;
    }

    private List<SettlementTxParticipant> rows(UUID txId) {
        return coordinator.rowsOf(txId);
    }

    private ClaimEvent event(UUID claimId, String type) {
        return events().forClaim(claimId).stream().filter(e -> e.eventType().equals(type)).findFirst()
                .orElseThrow(() -> new AssertionError(type + " not emitted; got " + events().typesFor(claimId)));
    }

    @Test
    void happyPath_allYes_commitsAndEveryoneGetsDoCommit() {
        UUID claimId = claimUnderReview("67500.00");
        UUID txId = approve(claimId);

        SettlementTx t = awaitStopped(txId, CoordinatorState.COMMITTED);
        assertThat(t.getAttempt()).isEqualTo(1);
        assertThat(t.getAmount()).isEqualByComparingTo("67500.00");

        for (FakeParticipant p : PARTICIPANTS) {
            assertThat(p.callsFor(txId)).as(p.name()).containsExactly("CanCommit", "PreCommit", "DoCommit");
            assertThat(p.stateOf(txId)).isEqualTo(ParticipantState.COMMITTED);
        }
        assertThat(rows(txId)).allSatisfy(r -> {
            assertThat(r.getVote()).isEqualTo(Vote.YES);
            assertThat(r.getState()).isEqualTo(TxState.COMMITTED);
            assertThat(r.getError()).isNull();
        });

        assertThat(CLAIMS.statusOf(claimId)).isEqualTo(ClaimStatus.APPROVED);
        assertThat(events().typesFor(claimId)).containsSubsequence(
                "CLAIM_APPROVED", "SETTLEMENT_STARTED", "TX_PRECOMMIT", "TX_DOCOMMIT", "TX_COMMITTED", "CLAIM_SETTLED");
        assertThat(events().typesFor(claimId).stream().filter("TX_VOTE"::equals)).hasSize(3);
        assertThat(event(claimId, "TX_COMMITTED").txId()).isEqualTo(txId);
        assertThat(event(claimId, "TX_PRECOMMIT").phase()).isEqualTo("PRE_COMMIT");
        assertThat(event(claimId, "CLAIM_SETTLED").toStatus()).isEqualTo("SETTLED");
    }

    @Test
    void paymentVotesNo_abortsAndEveryoneGetsAbort() {
        PAYMENT.voteNo("Payment gateway unavailable");
        UUID claimId = claimUnderReview("12000.00");
        UUID txId = approve(claimId);

        awaitStopped(txId, CoordinatorState.ABORTED);
        for (FakeParticipant p : PARTICIPANTS) {
            assertThat(p.callsFor(txId)).as(p.name()).containsExactly("CanCommit", "Abort");
        }
        assertThat(CLAIMS.statusOf(claimId)).isEqualTo(ClaimStatus.SETTLEMENT_FAILED);
        assertThat(CLAIMS.requestsFor(claimId).getLast().getIdempotencyKey()).isEqualTo(txId + ":SETTLEMENT_FAILED");

        SettlementView view = approvals.view(claimId).orElseThrow();
        assertThat(view.outcome()).isEqualTo("ABORTED");
        assertThat(view.phase()).isEqualTo("CAN_COMMIT");
        assertThat(view.abortReason()).isEqualTo("payment voted NO in CanCommit: Payment gateway unavailable");
        assertThat(view.participants()).filteredOn(p -> p.name().equals("PAYMENT")).singleElement()
                .satisfies(p -> assertThat(p.vote()).isEqualTo("NO"));
        assertThat(view.participants()).allSatisfy(p -> assertThat(p.aborted()).isTrue());
        assertThat(events().typesFor(claimId)).containsSubsequence("SETTLEMENT_STARTED", "TX_ABORT", "TX_ABORTED")
                .doesNotContain("TX_PRECOMMIT", "CLAIM_SETTLED");
    }

    @Test
    void participantTimesOutInPhaseOne_aborts() {
        POLICY.delayCanCommit(1500); // phase timeout is 500 ms in tests
        UUID claimId = claimUnderReview("5000.00");
        UUID txId = approve(claimId);

        awaitStopped(txId, CoordinatorState.ABORTED);
        SettlementTxParticipant policy = rows(txId).stream()
                .filter(r -> r.getParticipant().equals("policy")).findFirst().orElseThrow();
        assertThat(policy.getVote()).isNull();
        assertThat(policy.getError()).isEqualTo("No reply within 500 ms");

        assertThat(INTAKE.callsFor(txId)).containsExactly("CanCommit", "Abort");
        assertThat(PAYMENT.callsFor(txId)).containsExactly("CanCommit", "Abort");
        assertThat(POLICY.callsFor(txId)).contains("Abort").doesNotContain("PreCommit", "DoCommit");
        assertThat(approvals.view(claimId).orElseThrow().abortReason())
                .isEqualTo("policy did not vote: No reply within 500 ms.");
    }

    @Test
    void crashAfterPrecommit_recoverySendsDoCommitAndCommits() {
        chaos.set(ChaosMode.CRASH_AFTER_PRECOMMIT);
        UUID claimId = claimUnderReview("40000.00");
        UUID txId = approve(claimId);

        // The "crash": the settlement thread stops right after PreCommit was logged and acknowledged.
        awaitStopped(txId, CoordinatorState.PRECOMMITTING);
        for (FakeParticipant p : PARTICIPANTS) {
            assertThat(p.callsFor(txId)).containsExactly("CanCommit", "PreCommit");
        }
        assertThat(chaos.mode()).as("one-shot").isEqualTo(ChaosMode.NONE);
        assertThat(event(claimId, "COORDINATOR_CRASHED").change()).containsEntry("state", "PRECOMMITTING");

        // "Restart": startup recovery reads PRECOMMITTING from the log and finishes with DoCommit.
        recovery.onStartup();

        assertThat(txs.findById(txId).orElseThrow().getState()).isEqualTo(CoordinatorState.COMMITTED);
        for (FakeParticipant p : PARTICIPANTS) {
            assertThat(p.callsFor(txId)).containsExactly("CanCommit", "PreCommit", "DoCommit");
        }
        assertThat(event(claimId, "TX_RECOVERY").change())
                .containsEntry("fromState", "PRECOMMITTING").containsEntry("action", "DO_COMMIT");
        assertThat(events().typesFor(claimId)).contains("CLAIM_SETTLED");
    }

    @Test
    void crashWhileWaitingForVotes_recoveryAborts() {
        chaos.set(ChaosMode.CRASH_BEFORE_PRECOMMIT);
        UUID claimId = claimUnderReview("8000.00");
        UUID txId = approve(claimId);

        awaitStopped(txId, CoordinatorState.WAITING_VOTES);
        assertThat(rows(txId)).allSatisfy(r -> assertThat(r.getVote()).isEqualTo(Vote.YES));

        recovery.onStartup();

        assertThat(txs.findById(txId).orElseThrow().getState()).isEqualTo(CoordinatorState.ABORTED);
        for (FakeParticipant p : PARTICIPANTS) {
            assertThat(p.callsFor(txId)).containsExactly("CanCommit", "Abort");
        }
        assertThat(CLAIMS.statusOf(claimId)).isEqualTo(ClaimStatus.SETTLEMENT_FAILED);
        assertThat(event(claimId, "TX_RECOVERY").change()).containsEntry("fromState", "WAITING_VOTES");
        assertThat(approvals.view(claimId).orElseThrow().abortReason())
                .isEqualTo(ThreePhaseCommitCoordinator.RESTART_ABORT_REASON);
    }

    @Test
    void retryAfterAbort_startsAttemptTwo() {
        PAYMENT.voteNo("Insufficient float");
        UUID claimId = claimUnderReview("15000.00");
        UUID first = approve(claimId);
        awaitStopped(first, CoordinatorState.ABORTED);

        PAYMENT.reset();
        DecisionResponse retry = approvals.retry(claimId, ADJUSTER);

        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(retry.txId()).isNotEqualTo(first);
        SettlementTx second = awaitStopped(retry.txId(), CoordinatorState.COMMITTED);
        assertThat(second.getAttempt()).isEqualTo(2);
        assertThat(txs.findById(first).orElseThrow().getState()).isEqualTo(CoordinatorState.ABORTED);
        assertThat(approvals.view(claimId).orElseThrow().attempt()).isEqualTo(2);
        assertThat(events().typesFor(claimId)).contains("SETTLEMENT_RETRIED");

        assertThatThrownBy(() -> approvals.retry(claimId, ADJUSTER))
                .isInstanceOf(ApiException.class).hasMessage("This claim has already been settled.")
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void retryIsRefusedWhileASettlementIsRunning() {
        INTAKE.delayCanCommit(300);
        UUID claimId = claimUnderReview("1000.00");
        UUID txId = approve(claimId);

        assertThatThrownBy(() -> approvals.retry(claimId, ADJUSTER))
                .isInstanceOf(ApiException.class).hasMessage("A settlement is already running for this claim.");
        awaitStopped(txId, CoordinatorState.COMMITTED);
    }

    @Test
    void missingDoCommitAck_staysCommittingUntilRecoveryResends() {
        PAYMENT.failDoCommit(true);
        UUID claimId = claimUnderReview("22000.00");
        UUID txId = approve(claimId);

        awaitStopped(txId, CoordinatorState.COMMITTING);
        assertThat(events().typesFor(claimId)).contains("TX_DOCOMMIT_INCOMPLETE").doesNotContain("CLAIM_SETTLED");

        recovery.recoverStale(); // payment still down: stays COMMITTING
        assertThat(txs.findById(txId).orElseThrow().getState()).isEqualTo(CoordinatorState.COMMITTING);

        PAYMENT.failDoCommit(false);
        recovery.recoverStale();
        assertThat(txs.findById(txId).orElseThrow().getState()).isEqualTo(CoordinatorState.COMMITTED);
        assertThat(PAYMENT.callsFor(txId)).containsExactly("CanCommit", "PreCommit", "DoCommit", "DoCommit", "DoCommit");
    }
}
