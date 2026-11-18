package com.claims.participant.contract;

import com.claims.participant.IllegalTxStateException;
import com.claims.participant.ParticipantTx;
import com.claims.participant.ParticipantTxState;
import com.claims.participant.ThreePhaseParticipant;
import com.claims.participant.TxNotFoundException;
import com.claims.participant.VoteResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The test cases every 3PC participant (Claim Intake, Policy, Payment) must pass. Extend it in the
 * participant's own test sources (depend on {@code participant} with {@code <type>test-jar</type>}) and fill
 * in the fixtures; the cases run against the real participant and its database.
 *
 * <p>Effects are checked through the fixture methods: a participant decides what "prepared", "committed" and
 * "released" mean for its own data (claim status, reserved limit, authorised payment …).
 */
public abstract class ThreePhaseParticipantContract<T extends ParticipantTx> {

    // ------------------------------------------------------------------ fixtures

    protected abstract ThreePhaseParticipant<T> participant();

    /** Moves the participant's clock (the same clock the participant uses). */
    protected abstract void advanceClock(Duration duration);

    protected abstract Duration prepareTimeout();

    protected abstract Duration precommitTimeout();

    /** Creates data for which this participant must vote YES; returns the claim id. */
    protected abstract UUID committableClaim();

    /** Creates data for which this participant must vote NO; returns the claim id. */
    protected abstract UUID uncommittableClaim();

    /** The settlement amount used with {@code claimId}. */
    protected BigDecimal amountFor(UUID claimId) {
        return new BigDecimal("1000.00");
    }

    /** The YES vote's reservation is in place, nothing is final yet. */
    protected abstract void assertPrepared(UUID claimId);

    /** The settlement's effect is final (applied exactly once). */
    protected abstract void assertCommitted(UUID claimId);

    /** The reservation was released after a YES vote. */
    protected abstract void assertReleased(UUID claimId);

    /** Nothing was reserved or changed (after a NO vote). */
    protected abstract void assertUntouched(UUID claimId);

    // ------------------------------------------------------------------ helpers

    private VoteResult canCommit(UUID txId, UUID claimId) {
        return participant().canCommit(txId, claimId, amountFor(claimId), 1);
    }

    private ParticipantTxState state(UUID txId) {
        return participant().stateOf(txId).orElse(null);
    }

    // ------------------------------------------------------------------ cases

    @Test
    void contract_yesVote_preCommit_doCommit_commits() {
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();

        assertThat(canCommit(txId, claimId).yes()).isTrue();
        assertThat(state(txId)).isEqualTo(ParticipantTxState.PREPARED);
        assertPrepared(claimId);

        assertThat(participant().preCommit(txId)).isEqualTo(ParticipantTxState.PRECOMMITTED);
        assertThat(participant().doCommit(txId)).isEqualTo(ParticipantTxState.COMMITTED);
        assertCommitted(claimId);
    }

    @Test
    void contract_everyCallIsIdempotent() {
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();

        assertThat(canCommit(txId, claimId).yes()).isTrue();
        assertThat(canCommit(txId, claimId).yes()).as("repeated CanCommit").isTrue();
        assertPrepared(claimId);

        assertThat(participant().preCommit(txId)).isEqualTo(ParticipantTxState.PRECOMMITTED);
        assertThat(participant().preCommit(txId)).isEqualTo(ParticipantTxState.PRECOMMITTED);
        assertThat(participant().doCommit(txId)).isEqualTo(ParticipantTxState.COMMITTED);
        assertThat(participant().doCommit(txId)).isEqualTo(ParticipantTxState.COMMITTED);
        assertThat(participant().preCommit(txId)).as("late PreCommit").isEqualTo(ParticipantTxState.COMMITTED);
        assertCommitted(claimId);
    }

    @Test
    void contract_noVote_reservesNothing_andAbortIsHarmless() {
        UUID claimId = uncommittableClaim();
        UUID txId = UUID.randomUUID();

        VoteResult vote = canCommit(txId, claimId);
        assertThat(vote.yes()).isFalse();
        assertThat(vote.reason()).isNotBlank();
        assertThat(state(txId)).isEqualTo(ParticipantTxState.ABORTED);
        assertThat(canCommit(txId, claimId).yes()).as("repeated CanCommit stays NO").isFalse();

        assertThat(participant().abort(txId)).isEqualTo(ParticipantTxState.ABORTED);
        assertUntouched(claimId);
    }

    @Test
    void contract_abortAfterYes_releasesTheReservation() {
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();
        assertThat(canCommit(txId, claimId).yes()).isTrue();

        assertThat(participant().abort(txId)).isEqualTo(ParticipantTxState.ABORTED);
        assertThat(participant().abort(txId)).as("repeated Abort").isEqualTo(ParticipantTxState.ABORTED);
        assertReleased(claimId);
        assertThat(participant().preCommit(txId)).as("PreCommit after Abort reports ABORTED")
                .isEqualTo(ParticipantTxState.ABORTED);
    }

    @Test
    void contract_doCommitWithoutPreCommit_commits() {
        // The coordinator only sends DoCommit once everyone voted YES, so a lost PreCommit is not a reason to refuse.
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();
        assertThat(canCommit(txId, claimId).yes()).isTrue();

        assertThat(participant().doCommit(txId)).isEqualTo(ParticipantTxState.COMMITTED);
        assertCommitted(claimId);
    }

    @Test
    void contract_abortAfterCommit_isRefused() {
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();
        canCommit(txId, claimId);
        participant().doCommit(txId);

        assertThatThrownBy(() -> participant().abort(txId)).isInstanceOf(IllegalTxStateException.class);
        assertCommitted(claimId);
    }

    @Test
    void contract_doCommitAfterAbort_isRefused() {
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();
        canCommit(txId, claimId);
        participant().abort(txId);

        assertThatThrownBy(() -> participant().doCommit(txId)).isInstanceOf(IllegalTxStateException.class);
        assertReleased(claimId);
    }

    @Test
    void contract_abortBeforeCanCommit_leavesATombstone_andTheLateCanCommitVotesNo() {
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();

        assertThat(participant().abort(txId)).isEqualTo(ParticipantTxState.ABORTED);
        assertThat(canCommit(txId, claimId).yes()).isFalse();
        assertUntouched(claimId);
    }

    @Test
    void contract_preparedWithoutPreCommit_abortsAfterTheTimeout() {
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();
        canCommit(txId, claimId);

        advanceClock(prepareTimeout().minusSeconds(1));
        participant().expireTimedOut();
        assertThat(state(txId)).as("not yet").isEqualTo(ParticipantTxState.PREPARED);

        advanceClock(Duration.ofSeconds(2));
        assertThat(participant().expireTimedOut()).isGreaterThanOrEqualTo(1);
        assertThat(state(txId)).isEqualTo(ParticipantTxState.ABORTED);
        assertReleased(claimId);
    }

    @Test
    void contract_precommittedWithoutDoCommit_commitsAfterTheTimeout() {
        UUID claimId = committableClaim();
        UUID txId = UUID.randomUUID();
        canCommit(txId, claimId);
        participant().preCommit(txId);

        advanceClock(precommitTimeout().plusSeconds(1));
        assertThat(participant().expireTimedOut()).isGreaterThanOrEqualTo(1);
        assertThat(state(txId)).isEqualTo(ParticipantTxState.COMMITTED);
        assertCommitted(claimId);
        assertThat(participant().doCommit(txId)).as("the coordinator's late DoCommit").isEqualTo(ParticipantTxState.COMMITTED);
    }

    @Test
    void contract_unknownTx_preCommitAndDoCommitAreNotFound() {
        UUID txId = UUID.randomUUID();
        assertThatThrownBy(() -> participant().preCommit(txId)).isInstanceOf(TxNotFoundException.class);
        assertThatThrownBy(() -> participant().doCommit(txId)).isInstanceOf(TxNotFoundException.class);
    }
}
