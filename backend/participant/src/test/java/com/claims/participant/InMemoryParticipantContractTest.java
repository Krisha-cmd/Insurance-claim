package com.claims.participant;

import com.claims.participant.contract.MutableClock;
import com.claims.participant.contract.ThreePhaseParticipantContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the contract against a toy participant (a map of "accounts" that are reserved, then debited), which
 * checks the base class and the contract itself without a database.
 */
class InMemoryParticipantContractTest extends ThreePhaseParticipantContract<InMemoryParticipantContractTest.ToyTx> {

    static class ToyTx extends ParticipantTx {
    }

    enum Account { OPEN, RESERVED, DEBITED, CLOSED }

    static class Store implements ParticipantTxStore<ToyTx> {
        final Map<UUID, ToyTx> rows = new ConcurrentHashMap<>();

        public Optional<ToyTx> findTx(UUID txId) { return Optional.ofNullable(rows.get(txId)); }
        public ToyTx saveTx(ToyTx tx) { rows.put(tx.getTxId(), tx); return tx; }
        public List<ToyTx> findStale(ParticipantTxState state, Instant before) {
            return rows.values().stream().filter(t -> t.getState() == state && !t.getUpdatedAt().isAfter(before)).toList();
        }
    }

    static class Toy extends ThreePhaseParticipant<ToyTx> {
        final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
        final Map<UUID, Integer> debits = new ConcurrentHashMap<>();

        Toy(Store store, MutableClock clock) {
            super("toy", store, TransactionOperations.withoutTransaction(), clock,
                    new ParticipantTimeouts(Duration.ofMinutes(5), Duration.ofMinutes(2)));
        }

        @Override protected ToyTx newTx() { return new ToyTx(); }

        @Override protected VoteResult prepare(ToyTx tx) {
            if (accounts.get(tx.getClaimId()) != Account.OPEN) return VoteResult.no("account is not open");
            accounts.put(tx.getClaimId(), Account.RESERVED);
            return VoteResult.YES;
        }

        @Override protected void onCommit(ToyTx tx) {
            accounts.put(tx.getClaimId(), Account.DEBITED);
            debits.merge(tx.getClaimId(), 1, Integer::sum);
        }

        @Override protected void onAbort(ToyTx tx, ParticipantTxState previous) {
            accounts.put(tx.getClaimId(), Account.OPEN);
        }
    }

    private MutableClock clock;
    private Toy toy;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));
        toy = new Toy(new Store(), clock);
    }

    @Override protected ThreePhaseParticipant<ToyTx> participant() { return toy; }
    @Override protected void advanceClock(Duration d) { clock.advance(d); }
    @Override protected Duration prepareTimeout() { return Duration.ofMinutes(5); }
    @Override protected Duration precommitTimeout() { return Duration.ofMinutes(2); }

    @Override protected UUID committableClaim() {
        UUID id = UUID.randomUUID();
        toy.accounts.put(id, Account.OPEN);
        return id;
    }

    @Override protected UUID uncommittableClaim() {
        UUID id = UUID.randomUUID();
        toy.accounts.put(id, Account.CLOSED);
        return id;
    }

    @Override protected void assertPrepared(UUID c) { assertThat(toy.accounts.get(c)).isEqualTo(Account.RESERVED); }
    @Override protected void assertCommitted(UUID c) {
        assertThat(toy.accounts.get(c)).isEqualTo(Account.DEBITED);
        assertThat(toy.debits.get(c)).as("debited exactly once").isEqualTo(1);
    }
    @Override protected void assertReleased(UUID c) { assertThat(toy.accounts.get(c)).isEqualTo(Account.OPEN); }
    @Override protected void assertUntouched(UUID c) { assertThat(toy.accounts.get(c)).isIn(Account.OPEN, Account.CLOSED); }

    @Test
    void aPrepareThatThrowsCountsAsNo() {
        UUID claimId = UUID.randomUUID(); // no account at all: get() returns null, not OPEN
        Toy throwing = new Toy(new Store(), clock) {
            @Override protected VoteResult prepare(ToyTx tx) { throw new IllegalStateException("ledger offline"); }
        };
        VoteResult vote = throwing.canCommit(UUID.randomUUID(), claimId, null, 1);
        assertThat(vote.yes()).isFalse();
        assertThat(vote.reason()).isEqualTo("Could not prepare: ledger offline");
    }
}
