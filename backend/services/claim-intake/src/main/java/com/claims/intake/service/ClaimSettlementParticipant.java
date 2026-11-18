package com.claims.intake.service;

import com.claims.intake.config.IntakeProperties;
import com.claims.intake.domain.Actor;
import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStateMachine;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.domain.SettlementParticipation;
import com.claims.intake.repository.ClaimRepository;
import com.claims.intake.repository.SettlementParticipationRepository;
import com.claims.participant.ParticipantTimeouts;
import com.claims.participant.ParticipantTxState;
import com.claims.participant.ThreePhaseParticipant;
import com.claims.participant.VoteResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Claim Intake's side of the settlement 3PC: the claim itself.
 * <ul>
 *   <li>prepare: YES only if the claim is APPROVED (or SETTLEMENT_FAILED, for a retry) and the amount is
 *       positive and not above the claimed amount; the claim becomes SETTLING.</li>
 *   <li>commit: the claim becomes SETTLED with approved_amount = the settled amount.</li>
 *   <li>abort: a SETTLING (or still APPROVED) claim becomes SETTLEMENT_FAILED.</li>
 * </ul>
 */
@Component
public class ClaimSettlementParticipant extends ThreePhaseParticipant<SettlementParticipation> {

    private static final Set<ClaimStatus> SETTLEABLE = EnumSet.of(ClaimStatus.APPROVED, ClaimStatus.SETTLEMENT_FAILED);
    private static final Set<ClaimStatus> FAILS_ON_ABORT = EnumSet.of(ClaimStatus.SETTLING, ClaimStatus.APPROVED);

    private final ClaimRepository claims;
    private final ClaimStateMachine stateMachine;
    private final IntakeEvents events;
    private final Clock clock;

    public ClaimSettlementParticipant(SettlementParticipationRepository store, TransactionTemplate tx, Clock clock,
                                      IntakeProperties props, ClaimRepository claims, ClaimStateMachine stateMachine,
                                      IntakeEvents events) {
        super(Actor.SERVICE, store, tx, clock,
                new ParticipantTimeouts(props.participant().prepareTimeout(), props.participant().precommitTimeout()));
        this.claims = claims;
        this.stateMachine = stateMachine;
        this.events = events;
        this.clock = clock;
    }

    @Override
    protected SettlementParticipation newTx() {
        return new SettlementParticipation();
    }

    @Override
    protected VoteResult prepare(SettlementParticipation tx) {
        Optional<Claim> found = claims.findById(tx.getClaimId());
        if (found.isEmpty()) {
            return VoteResult.no("Claim " + tx.getClaimId() + " not found");
        }
        Claim c = found.get();
        if (!SETTLEABLE.contains(c.getStatus())) {
            return VoteResult.no("Claim is " + c.getStatus() + ", not APPROVED");
        }
        if (tx.getAmount() == null || tx.getAmount().signum() <= 0) {
            return VoteResult.no("Settlement amount must be positive");
        }
        if (tx.getAmount().compareTo(c.getClaimedAmount()) > 0) {
            return VoteResult.no("Settlement amount " + tx.getAmount().toPlainString()
                    + " exceeds the claimed amount " + c.getClaimedAmount().toPlainString());
        }
        stateMachine.transition(c, ClaimStatus.SETTLING, Actor.intake(),
                "Settlement " + tx.getTxId() + " prepared (attempt " + tx.getAttempt() + ")");
        return VoteResult.YES;
    }

    @Override
    protected void onCommit(SettlementParticipation tx) {
        Claim c = claims.findById(tx.getClaimId()).orElseThrow();
        c.recordApprovedAmount(tx.getAmount());
        stateMachine.transition(c, ClaimStatus.SETTLED, Actor.intake(), "Settlement " + tx.getTxId() + " committed");
        claims.save(c);
    }

    @Override
    protected void onAbort(SettlementParticipation tx, ParticipantTxState previous) {
        Claim c = claims.findById(tx.getClaimId()).orElseThrow();
        if (FAILS_ON_ABORT.contains(c.getStatus())) {
            stateMachine.transition(c, ClaimStatus.SETTLEMENT_FAILED, Actor.intake(),
                    "Settlement " + tx.getTxId() + " aborted" + (tx.getReason() != null ? ": " + tx.getReason() : ""));
        }
    }

    /** TX_VOTE for the vote, TX_PARTICIPANT_STATE for every later change (read by the Audit Flink job). */
    @Override
    protected void onTransition(SettlementParticipation tx, ParticipantTxState from) {
        if (tx.getClaimId() == null) {
            return; // tombstone
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from == null ? null : from.name());
        change.put("reason", tx.getReason());
        events.participant(from == null ? "TX_VOTE" : "TX_PARTICIPANT_STATE", tx.getClaimId(), tx.getTxId(),
                tx.getVote() == null ? null : tx.getVote().name(), tx.getState().name(), change, clock.instant());
    }

    /** The participant's own timeouts (PREPARED → abort, PRECOMMITTED → commit). */
    @Scheduled(initialDelayString = "${claims.intake.participant.sweep-interval}",
               fixedDelayString = "${claims.intake.participant.sweep-interval}")
    public void sweep() {
        expireTimedOut();
    }
}
