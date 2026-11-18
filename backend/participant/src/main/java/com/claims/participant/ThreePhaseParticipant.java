package com.claims.participant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The participant side of three-phase commit. Subclasses supply the business effect; this class owns the
 * protocol, its log ({@link ParticipantTx}) and its timeouts. Each call runs in one database transaction, so
 * the business change and the log row commit together.
 *
 * <pre>
 *              CanCommit                 PreCommit                DoCommit
 *   (none) ──YES (prepare)──▶ PREPARED ──────────▶ PRECOMMITTED ─────────▶ COMMITTED (onCommit)
 *     │                          │  ╲ DoCommit ───────────────────────────▶ COMMITTED
 *     └──NO / Abort──▶ ABORTED ◀─┴── Abort, or no PreCommit within prepareTimeout (onAbort)
 *                                         PRECOMMITTED + no DoCommit within precommitTimeout → COMMITTED
 * </pre>
 *
 * <p>Rules (checked by {@code ThreePhaseParticipantContract}):
 * <ul>
 *   <li>Every call is idempotent: repeating it returns the current state and applies no effect twice.</li>
 *   <li>CanCommit after an Abort (a tombstone) votes NO.</li>
 *   <li>DoCommit on an ABORTED tx and Abort on a COMMITTED tx are refused ({@link IllegalTxStateException}).</li>
 *   <li>PreCommit/DoCommit for an unknown tx: {@link TxNotFoundException}.</li>
 * </ul>
 */
public abstract class ThreePhaseParticipant<T extends ParticipantTx> {

    private static final Logger log = LoggerFactory.getLogger(ThreePhaseParticipant.class);

    private final String name;
    private final ParticipantTxStore<T> store;
    private final TransactionOperations tx;
    private final Clock clock;
    private final ParticipantTimeouts timeouts;

    protected ThreePhaseParticipant(String name, ParticipantTxStore<T> store, TransactionOperations tx,
                                    Clock clock, ParticipantTimeouts timeouts) {
        this.name = name;
        this.store = store;
        this.tx = tx;
        this.clock = clock;
        this.timeouts = timeouts;
    }

    // ------------------------------------------------------------------ business hooks

    /** A new, empty log row of the subclass's entity type. */
    protected abstract T newTx();

    /**
     * Phase 1: can this settlement go ahead? Reserve what is needed and vote. A thrown exception counts as NO
     * (its effects are rolled back).
     */
    protected abstract VoteResult prepare(T tx);

    /** Phase 2: everyone voted YES. Nothing to do for most participants. */
    protected void onPreCommit(T tx) {
    }

    /** Make the prepared change final. Runs once per tx. */
    protected abstract void onCommit(T tx);

    /** Release what {@link #prepare} reserved. Runs once, and only for a tx that voted YES. */
    protected abstract void onAbort(T tx, ParticipantTxState previous);

    /** Called inside the transaction after every state change, e.g. to publish an event. */
    protected void onTransition(T tx, ParticipantTxState from) {
    }

    // ------------------------------------------------------------------ protocol

    public String name() {
        return name;
    }

    public VoteResult canCommit(UUID txId, UUID claimId, BigDecimal amount, int attempt) {
        Optional<T> existing = store.findTx(txId);
        if (existing.isPresent()) {
            return voteOf(existing.get());
        }
        try {
            return tx.execute(status -> {
                T t = fresh(txId, claimId, amount, attempt);
                VoteResult vote = prepare(t);
                if (vote.yes()) t.votedYes(now());
                else t.votedNo(vote.reason(), now());
                store.saveTx(t);
                onTransition(t, null);
                log.info("[{}] tx {} claim {}: voted {}{}", name, txId, claimId, vote.yes() ? "YES" : "NO",
                        vote.yes() ? "" : " (" + vote.reason() + ")");
                return vote;
            });
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent duplicate CanCommit won the insert; answer what it decided.
            return store.findTx(txId).map(this::voteOf).orElseThrow(() -> duplicate);
        } catch (RuntimeException e) {
            String reason = "Could not prepare: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.warn("[{}] tx {} claim {}: prepare failed, voting NO", name, txId, claimId, e);
            tx.executeWithoutResult(status -> {
                T t = fresh(txId, claimId, amount, attempt);
                t.votedNo(reason, now());
                store.saveTx(t);
                onTransition(t, null);
            });
            return VoteResult.no(reason);
        }
    }

    public ParticipantTxState preCommit(UUID txId) {
        return tx.execute(status -> {
            T t = load(txId);
            if (t.getState() == ParticipantTxState.PREPARED) {
                t.move(ParticipantTxState.PRECOMMITTED, null, now());
                onPreCommit(t);
                save(t, ParticipantTxState.PREPARED);
            }
            return t.getState(); // PRECOMMITTED/COMMITTED: repeat; ABORTED: the coordinator sees no ack
        });
    }

    public ParticipantTxState doCommit(UUID txId) {
        return tx.execute(status -> {
            T t = load(txId);
            switch (t.getState()) {
                case PREPARED, PRECOMMITTED -> commit(t, "DoCommit");
                case COMMITTED -> { }
                case ABORTED -> throw new IllegalTxStateException("Settlement " + txId + " is already ABORTED"
                        + (t.getReason() != null ? ": " + t.getReason() : ""));
            }
            return t.getState();
        });
    }

    public ParticipantTxState abort(UUID txId) {
        return tx.execute(status -> {
            Optional<T> found = store.findTx(txId);
            if (found.isEmpty()) {
                // Abort overtook CanCommit: leave a tombstone so a late CanCommit votes NO.
                T t = fresh(txId, null, null, 0);
                t.votedNo("Aborted before CanCommit arrived", now());
                store.saveTx(t);
                onTransition(t, null);
                return ParticipantTxState.ABORTED;
            }
            T t = found.get();
            switch (t.getState()) {
                case PREPARED, PRECOMMITTED -> abortPrepared(t, "Abort");
                case ABORTED -> { }
                case COMMITTED -> throw new IllegalTxStateException("Settlement " + txId + " is already COMMITTED");
            }
            return t.getState();
        });
    }

    /**
     * Applies the participant timeouts: PREPARED too long → abort, PRECOMMITTED too long → commit.
     * Call it periodically (e.g. {@code @Scheduled}). Each tx is handled in its own transaction.
     *
     * @return how many transactions were finished
     */
    public int expireTimedOut() {
        Instant now = now();
        int finished = 0;
        for (T stale : store.findStale(ParticipantTxState.PREPARED, now.minus(timeouts.prepareTimeout()))) {
            finished += timeout(stale.getTxId(), ParticipantTxState.PREPARED);
        }
        for (T stale : store.findStale(ParticipantTxState.PRECOMMITTED, now.minus(timeouts.precommitTimeout()))) {
            finished += timeout(stale.getTxId(), ParticipantTxState.PRECOMMITTED);
        }
        return finished;
    }

    public Optional<ParticipantTxState> stateOf(UUID txId) {
        return store.findTx(txId).map(ParticipantTx::getState);
    }

    // ------------------------------------------------------------------ internals

    private int timeout(UUID txId, ParticipantTxState expected) {
        try {
            Boolean done = tx.execute(status -> {
                T t = load(txId);
                if (t.getState() != expected) return false; // changed meanwhile
                if (expected == ParticipantTxState.PREPARED) {
                    abortPrepared(t, "No PreCommit within " + timeouts.prepareTimeout() + " (participant timeout)");
                } else {
                    commit(t, "No DoCommit within " + timeouts.precommitTimeout() + " (participant timeout)");
                }
                return true;
            });
            return Boolean.TRUE.equals(done) ? 1 : 0;
        } catch (RuntimeException e) {
            log.warn("[{}] timeout handling for tx {} failed; will retry", name, txId, e);
            return 0;
        }
    }

    private void commit(T t, String why) {
        ParticipantTxState from = t.getState();
        t.move(ParticipantTxState.COMMITTED, null, now());
        onCommit(t);
        save(t, from);
        log.info("[{}] tx {} claim {}: COMMITTED ({})", name, t.getTxId(), t.getClaimId(), why);
    }

    private void abortPrepared(T t, String why) {
        ParticipantTxState from = t.getState();
        t.move(ParticipantTxState.ABORTED, why, now());
        onAbort(t, from);
        save(t, from);
        log.info("[{}] tx {} claim {}: ABORTED from {} ({})", name, t.getTxId(), t.getClaimId(), from, why);
    }

    private void save(T t, ParticipantTxState from) {
        store.saveTx(t);
        onTransition(t, from);
    }

    private T load(UUID txId) {
        return store.findTx(txId).orElseThrow(() -> new TxNotFoundException(txId));
    }

    private T fresh(UUID txId, UUID claimId, BigDecimal amount, int attempt) {
        T t = newTx();
        t.init(txId, claimId, amount, attempt, now());
        return t;
    }

    private VoteResult voteOf(T t) {
        if (t.getVote() == ParticipantTx.Vote.YES && t.getState() != ParticipantTxState.ABORTED) {
            return VoteResult.YES;
        }
        return VoteResult.no(t.getReason() != null ? t.getReason() : "Settlement was aborted");
    }

    private Instant now() {
        return clock.instant();
    }
}
