package com.claims.approval.service;

import com.claims.approval.config.ApprovalProperties;
import com.claims.approval.config.CoordinatorProperties;
import com.claims.approval.domain.CoordinatorState;
import com.claims.approval.domain.SettlementTx;
import com.claims.approval.domain.SettlementTxParticipant;
import com.claims.approval.domain.TxState;
import com.claims.approval.domain.Vote;
import com.claims.common.event.EventPublisher;
import com.claims.approval.events.Events;
import com.claims.approval.grpc.ClaimClient;
import com.claims.approval.grpc.ParticipantClient;
import com.claims.approval.grpc.ParticipantClientRegistry;
import com.claims.common.error.ApiException;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.settlement.v1.AckReply;
import com.claims.proto.settlement.v1.CanCommitRequest;
import com.claims.proto.settlement.v1.VoteReply;
import com.claims.approval.repository.SettlementTxParticipantRepository;
import com.claims.approval.repository.SettlementTxRepository;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The 3PC coordinator. Rule: the next state is written to the log (settlement_tx) BEFORE the phase's
 * messages go out, so whatever point a crash hits, {@link CoordinatorRecovery} can finish the job:
 * <ul>
 *   <li>WAITING_VOTES: not everyone is known to have voted YES, so abort.</li>
 *   <li>PRECOMMITTING / COMMITTING: everyone voted YES, so commit.</li>
 *   <li>ABORTING: finish the abort.</li>
 * </ul>
 * Every participant call is idempotent, so repeating a phase after a crash is safe.
 */
@Service
public class ThreePhaseCommitCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ThreePhaseCommitCoordinator.class);

    static final String RESTART_ABORT_REASON =
            "The coordinator restarted before all votes were collected, so the settlement was aborted.";

    private final SettlementTxRepository txs;
    private final SettlementTxParticipantRepository participants;
    private final ParticipantClientRegistry registry;
    private final ClaimClient claims;
    private final ChaosSwitch chaos;
    private final EventPublisher audit;
    private final TransactionTemplate tx;
    private final TaskExecutor settlementExecutor;
    private final ExecutorService callExecutor;
    private final Clock clock;
    private final Duration phaseTimeout;
    private final String currency;

    /** Settlements this process is working on right now; recovery leaves them alone. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public ThreePhaseCommitCoordinator(SettlementTxRepository txs, SettlementTxParticipantRepository participants,
                                       ParticipantClientRegistry registry, ClaimClient claims, ChaosSwitch chaos,
                                       EventPublisher audit, TransactionTemplate tx,
                                       @Qualifier("settlementExecutor") TaskExecutor settlementExecutor,
                                       @Qualifier("participantCallExecutor") ExecutorService callExecutor,
                                       Clock clock, CoordinatorProperties coordinatorProps, ApprovalProperties approvalProps) {
        this.txs = txs;
        this.participants = participants;
        this.registry = registry;
        this.claims = claims;
        this.chaos = chaos;
        this.audit = audit;
        this.tx = tx;
        this.settlementExecutor = settlementExecutor;
        this.callExecutor = callExecutor;
        this.clock = clock;
        this.phaseTimeout = coordinatorProps.phaseTimeout();
        this.currency = approvalProps.currency();
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Logs a new settlement (WAITING_VOTES, attempt = previous + 1) and runs it in the background.
     *
     * @return the new transaction id
     * @throws ApiException 409 if this claim already has an unfinished settlement
     */
    public UUID start(UUID claimId, BigDecimal amount) {
        Instant now = clock.instant();
        SettlementTx created;
        try {
            created = tx.execute(status -> {
                int attempt = txs.findFirstByClaimIdOrderByAttemptDesc(claimId).map(t -> t.getAttempt() + 1).orElse(1);
                SettlementTx t = txs.save(SettlementTx.begin(claimId, amount, attempt, now));
                for (ParticipantClient p : registry.all()) {
                    participants.save(new SettlementTxParticipant(t.getTxId(), p.name()));
                }
                txs.flush();
                return t;
            });
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "A settlement is already running for this claim.");
        }

        UUID txId = created.getTxId();
        publish("TX_STARTED", created, "CAN_COMMIT", details(
                "attempt", created.getAttempt(),
                "amount", amount,
                "currency", currency,
                "participants", registry.all().stream().map(ParticipantClient::name).toList()));
        log.info("Settlement {} started for claim {} (attempt {}, amount {})", txId, claimId, created.getAttempt(), amount);

        inFlight.add(txId);
        try {
            settlementExecutor.execute(() -> run(txId));
        } catch (TaskRejectedException e) {
            inFlight.remove(txId);
            log.warn("Settlement {} could not be scheduled now; the recovery sweep will abort it", txId);
        }
        return txId;
    }

    /** Finishes an unfinished settlement from its logged state. Used by {@link CoordinatorRecovery}. */
    public void recover(UUID txId) {
        if (!inFlight.add(txId)) {
            return; // this process is working on it
        }
        try {
            SettlementTx t = txs.findById(txId).orElse(null);
            if (t == null || t.getState().isFinal()) {
                return;
            }
            CoordinatorState from = t.getState();
            String action = switch (from) {
                case WAITING_VOTES, ABORTING -> "ABORT";
                case PRECOMMITTING, COMMITTING -> "DO_COMMIT";
                default -> "NONE";
            };
            log.warn("Recovering settlement {} from {}: {}", txId, from, action);
            publish("TX_RECOVERY", t, from.phase(), details("fromState", from.name(), "action", action));

            switch (from) {
                case WAITING_VOTES, ABORTING -> {
                    String reason = abortReason(participants.findByIdTxId(txId));
                    abort(t, reason != null ? reason : RESTART_ABORT_REASON);
                }
                case PRECOMMITTING, COMMITTING -> commit(t);
                default -> { }
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Settlement {} was updated by someone else during recovery; leaving it", txId);
        } catch (RuntimeException e) {
            log.error("Recovery of settlement {} failed; the next sweep will try again", txId, e);
        } finally {
            inFlight.remove(txId);
        }
    }

    public boolean isInFlight(UUID txId) {
        return inFlight.contains(txId);
    }

    // ------------------------------------------------------------------ the protocol

    private void run(UUID txId) {
        try {
            SettlementTx t = txs.findById(txId).orElseThrow();

            // Phase 1: CanCommit. WAITING_VOTES was logged by start().
            if (!collectVotes(t)) {
                String reason = abortReason(participants.findByIdTxId(txId));
                abort(t, reason != null ? reason : "Not every participant voted YES.");
                return;
            }
            crashPoint(t, CoordinatorState.WAITING_VOTES);

            // Phase 2: PreCommit.
            t = transition(t, CoordinatorState.PRECOMMITTING);
            publish("TX_PRECOMMIT", t, "PRE_COMMIT", details());
            preCommitAll(t);
            crashPoint(t, CoordinatorState.PRECOMMITTING);

            // Phase 3: DoCommit.
            commit(t);
        } catch (RuntimeException e) {
            log.error("Settlement {} stopped unexpectedly; CoordinatorRecovery will finish it from the log", txId, e);
        } finally {
            inFlight.remove(txId);
        }
    }

    /** Sends CanCommit to everyone in parallel and records each vote. True only if all voted YES in time. */
    private boolean collectVotes(SettlementTx t) {
        CanCommitRequest request = CanCommitRequest.newBuilder()
                .setTxId(t.getTxId().toString())
                .setClaimId(t.getClaimId().toString())
                .setAmount(t.getAmount().toPlainString())
                .setCurrency(currency)
                .setAttempt(t.getAttempt())
                .build();

        Map<String, Boolean> yes = callAll(t, client -> client.canCommit(request),
                (row, reply) -> row.recordVote(votedYes(reply) ? Vote.YES : Vote.NO, noReason(reply), clock.instant()),
                VoteReply::getVote,
                (name, outcome) -> audit.publish(Events.vote(t.getClaimId(), t.getTxId(), name,
                        outcome.reply() == null ? "NONE" : votedYes(outcome.reply()) ? "YES" : "NO",
                        details("reason", outcome.reply() != null ? noReason(outcome.reply()) : outcome.error(),
                                "attempt", t.getAttempt()),
                        clock.instant())),
                ThreePhaseCommitCoordinator::votedYes);
        return !yes.isEmpty() && yes.values().stream().allMatch(Boolean::booleanValue);
    }

    private static boolean votedYes(VoteReply reply) {
        return reply.getVote() == com.claims.proto.settlement.v1.Vote.VOTE_YES;
    }

    /** The NO reason; null for a YES. An unspecified vote counts as NO. */
    private static String noReason(VoteReply reply) {
        if (votedYes(reply)) return null;
        return reply.getVote() == com.claims.proto.settlement.v1.Vote.VOTE_UNSPECIFIED ? "Returned no vote" : reply.getReason();
    }

    /** Sends PreCommit to everyone. A missing ack does not stop the commit: participants commit on timeout. */
    private void preCommitAll(SettlementTx t) {
        Map<String, Boolean> acked = callAll(t, client -> client.preCommit(t.getTxId()),
                (row, reply) -> row.recordAck(TxState.from(reply.getState()), clock.instant()),
                AckReply::getState, null,
                reply -> TxState.from(reply.getState()) == TxState.PRECOMMITTED);
        publish("TX_PRECOMMIT_ACKS", t, "PRE_COMMIT", ackSummary(acked));
        if (acked.containsValue(false)) {
            log.warn("Settlement {}: PreCommit not acknowledged by {}; continuing to DoCommit", t.getTxId(), missing(acked));
        }
    }

    /** Logs COMMITTING (unless already there), sends DoCommit, and logs COMMITTED once everyone acknowledged. */
    private void commit(SettlementTx t) {
        if (t.getState() == CoordinatorState.PRECOMMITTING) {
            t = transition(t, CoordinatorState.COMMITTING);
        }
        SettlementTx committing = t;
        publish("TX_DOCOMMIT", committing, "DO_COMMIT", details());

        Map<String, Boolean> acked = callAll(committing, client -> client.doCommit(committing.getTxId()),
                (row, reply) -> row.recordAck(TxState.from(reply.getState()), clock.instant()),
                AckReply::getState, null,
                reply -> TxState.from(reply.getState()) == TxState.COMMITTED);

        if (acked.containsValue(false)) {
            // The decision is commit and cannot change. Stay in COMMITTING; the recovery sweep resends DoCommit.
            log.warn("Settlement {}: DoCommit not acknowledged by {}; recovery will resend", committing.getTxId(), missing(acked));
            publish("TX_DOCOMMIT_INCOMPLETE", committing, "DO_COMMIT", ackSummary(acked));
            return;
        }
        SettlementTx done = transition(committing, CoordinatorState.COMMITTED);
        publish("TX_COMMITTED", done, "DONE", details("amount", done.getAmount()));
        audit.publish(Events.claimSettled(done.getClaimId(), done.getTxId(), done.getAmount(),
                details("currency", currency, "attempt", done.getAttempt()), clock.instant()));
        log.info("Settlement {} COMMITTED for claim {}", done.getTxId(), done.getClaimId());
    }

    /** Logs ABORTING (unless already there), sends Abort to everyone, logs ABORTED and fails the claim. */
    private void abort(SettlementTx t, String reason) {
        if (t.getState() == CoordinatorState.WAITING_VOTES) {
            t = transition(t, CoordinatorState.ABORTING);
        }
        SettlementTx aborting = t;
        publish("TX_ABORT", aborting, "CAN_COMMIT", details("reason", reason));

        Map<String, Boolean> acked = callAll(aborting, client -> client.abort(aborting.getTxId()),
                (row, reply) -> row.recordAck(TxState.from(reply.getState()), clock.instant()),
                AckReply::getState, null,
                reply -> TxState.from(reply.getState()) == TxState.ABORTED);
        // Participants that missed the Abort are at most PREPARED, and abort on their own timeout.
        SettlementTx done = transition(aborting, CoordinatorState.ABORTED);
        Map<String, Object> change = ackSummary(acked);
        change.put("reason", reason);
        publish("TX_ABORTED", done, "CAN_COMMIT", change);
        log.warn("Settlement {} ABORTED for claim {}: {}", done.getTxId(), done.getClaimId(), reason);

        // Intake's own Abort handler also sets this; both are idempotent.
        try {
            claims.updateStatus(done.getClaimId(), ClaimStatus.SETTLEMENT_FAILED, reason, Events.COORDINATOR,
                    done.getTxId() + ":SETTLEMENT_FAILED");
        } catch (ApiException e) {
            log.warn("Could not set claim {} to SETTLEMENT_FAILED ({}); Intake's abort handler sets it too",
                    done.getClaimId(), e.getMessage());
        }
    }

    private void crashPoint(SettlementTx t, CoordinatorState reached) {
        if (!chaos.armedFor(reached)) {
            return;
        }
        chaos.maybeCrash(reached, () -> audit.publishAndWait(
                Events.tx("COORDINATOR_CRASHED", t.getClaimId(), t.getTxId(), reached.phase(),
                        details("state", reached.name(), "chaos", chaos.mode().name()), clock.instant()),
                Duration.ofSeconds(2)));
    }

    // ------------------------------------------------------------------ plumbing

    private record Outcome<R>(R reply, String error) {
    }

    /**
     * Calls every participant of the tx in parallel (one virtual thread each), waits at most one phase
     * timeout, records each reply or failure on its row as it arrives, and returns name -> success.
     */
    private <R> Map<String, Boolean> callAll(SettlementTx t,
                                             Function<ParticipantClient, R> call,
                                             BiConsumer<SettlementTxParticipant, R> onReply,
                                             Function<R, ?> summarize,
                                             BiConsumer<String, Outcome<R>> afterEach,
                                             Function<R, Boolean> success) {
        List<SettlementTxParticipant> rows = rowsOf(t.getTxId());

        Map<String, CompletableFuture<Boolean>> futures = new LinkedHashMap<>();
        for (SettlementTxParticipant row : rows) {
            String name = row.getParticipant();
            CompletableFuture<R> reply = registry.get(name)
                    .map(client -> CompletableFuture.supplyAsync(() -> call.apply(client), callExecutor))
                    .orElseGet(() -> CompletableFuture.failedFuture(
                            new IllegalStateException("not configured in claims.coordinator.participants")));

            futures.put(name, reply
                    .orTimeout(phaseTimeout.toMillis() + 2000, TimeUnit.MILLISECONDS) // safety net; the gRPC deadline fires first
                    .handle((r, ex) -> {
                        Outcome<R> outcome = ex == null ? new Outcome<>(r, null) : new Outcome<>(null, describe(ex));
                        updateRow(t.getTxId(), name, outcome, onReply);
                        if (afterEach != null) afterEach.accept(name, outcome);
                        if (ex != null) {
                            log.warn("Settlement {}: {} failed: {}", t.getTxId(), name, outcome.error());
                            return false;
                        }
                        log.debug("Settlement {}: {} replied {}", t.getTxId(), name, summarize.apply(r));
                        return success.apply(r);
                    }));
        }

        Map<String, Boolean> result = new LinkedHashMap<>();
        futures.forEach((name, f) -> result.put(name, f.join()));
        return result;
    }

    private <R> void updateRow(UUID txId, String name, Outcome<R> outcome, BiConsumer<SettlementTxParticipant, R> onReply) {
        tx.executeWithoutResult(status -> participants.findById(new SettlementTxParticipant.Key(txId, name)).ifPresent(row -> {
            if (outcome.error() == null) onReply.accept(row, outcome.reply());
            else row.recordFailure(outcome.error());
            participants.save(row);
        }));
    }

    private String describe(Throwable ex) {
        Throwable e = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        if (e instanceof StatusRuntimeException sre) {
            return switch (sre.getStatus().getCode()) {
                case DEADLINE_EXCEEDED -> "No reply within " + human(phaseTimeout);
                case UNAVAILABLE -> "Unreachable (UNAVAILABLE)";
                default -> sre.getStatus().getCode() + (sre.getStatus().getDescription() != null
                        ? ": " + sre.getStatus().getDescription() : "");
            };
        }
        if (e instanceof TimeoutException) {
            return "No reply within " + human(phaseTimeout);
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String human(Duration d) {
        return d.toMillis() % 1000 == 0 ? d.toSeconds() + " s" : d.toMillis() + " ms";
    }

    private SettlementTx transition(SettlementTx t, CoordinatorState next) {
        t.moveTo(next, clock.instant());
        return txs.save(t); // version-checked; a concurrent change fails with ObjectOptimisticLockingFailureException
    }

    /** Why a settlement aborted, from what the participants answered. Null if they gave no reason. */
    static String abortReason(List<SettlementTxParticipant> rows) {
        for (SettlementTxParticipant r : rows) {
            if (r.getVote() == Vote.NO) {
                return r.getParticipant() + " voted NO in CanCommit: " + r.getError();
            }
        }
        for (SettlementTxParticipant r : rows) {
            if (r.getVote() == null && r.getError() != null) {
                return r.getParticipant() + " did not vote: " + r.getError() + ".";
            }
        }
        return null;
    }

    private void publish(String type, SettlementTx t, String phase, Map<String, Object> change) {
        change.putIfAbsent("state", t.getState().name());
        change.putIfAbsent("attempt", t.getAttempt());
        audit.publish(Events.tx(type, t.getClaimId(), t.getTxId(), phase, change, clock.instant()));
    }

    private static Map<String, Object> ackSummary(Map<String, Boolean> acked) {
        return details(
                "acknowledged", acked.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList(),
                "missing", missing(acked));
    }

    private static List<String> missing(Map<String, Boolean> acked) {
        return acked.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
    }

    /** A mutable map from key/value pairs (values may be null). */
    private static Map<String, Object> details(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Participant rows of a tx in configuration order. */
    List<SettlementTxParticipant> rowsOf(UUID txId) {
        List<SettlementTxParticipant> rows = new ArrayList<>(participants.findByIdTxId(txId));
        rows.sort((a, b) -> Integer.compare(registry.order(a.getParticipant()), registry.order(b.getParticipant())));
        return rows;
    }
}
