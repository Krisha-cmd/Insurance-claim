package com.claims.policy.service;

import com.claims.common.event.ClaimEvent;
import com.claims.common.event.EventPublisher;
import com.claims.participant.ParticipantTimeouts;
import com.claims.participant.ParticipantTxState;
import com.claims.participant.ThreePhaseParticipant;
import com.claims.participant.VoteResult;
import com.claims.policy.config.PolicyProperties;
import com.claims.policy.domain.LimitReservation;
import com.claims.policy.domain.Policy;
import com.claims.policy.domain.PolicyBalance;
import com.claims.policy.domain.PolicyStatus;
import com.claims.policy.domain.Rupees;
import com.claims.policy.grpc.ClaimLookupClient;
import com.claims.policy.repository.LimitReservationRepository;
import com.claims.policy.repository.PolicyBalanceRepository;
import com.claims.policy.repository.PolicyRepository;
import com.claims.proto.claim.v1.ClaimDetails;
import io.grpc.StatusRuntimeException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;

/**
 * Policy's side of the settlement 3PC: the payout is reserved against the policy's remaining sum insured.
 * <ul>
 *   <li>prepare: look up the claim's policy (ClaimService.GetClaim). YES only if the policy is ACTIVE and
 *       sum_insured − utilised − reserved ≥ amount; then reserved += amount. The balance row is version-checked,
 *       so of two concurrent reservations that together exceed the balance only one can succeed (the base
 *       class retries the loser, which then sees the new reservation and votes NO).</li>
 *   <li>commit: utilised += amount, reserved −= amount.</li>
 *   <li>abort: reserved −= amount (the base class calls this only for a YES vote, i.e. when a reservation exists).</li>
 * </ul>
 */
@Component
public class LimitReservationParticipant extends ThreePhaseParticipant<LimitReservation> {

    public static final String NAME = "policy";

    private final ClaimLookupClient claimLookup;
    private final PolicyRepository policies;
    private final PolicyBalanceRepository balances;
    private final EventPublisher events;
    private final Clock clock;

    public LimitReservationParticipant(LimitReservationRepository store, TransactionTemplate tx, Clock clock,
                                       PolicyProperties props, ClaimLookupClient claimLookup, PolicyRepository policies,
                                       PolicyBalanceRepository balances, EventPublisher events) {
        super(NAME, store, tx, clock,
                new ParticipantTimeouts(props.participant().prepareTimeout(), props.participant().precommitTimeout()));
        this.claimLookup = claimLookup;
        this.policies = policies;
        this.balances = balances;
        this.events = events;
        this.clock = clock;
    }

    @Override
    protected LimitReservation newTx() {
        return new LimitReservation();
    }

    @Override
    protected VoteResult prepare(LimitReservation tx) {
        if (tx.getAmount() == null || tx.getAmount().signum() <= 0) {
            return VoteResult.no("Settlement amount must be positive");
        }
        Optional<ClaimDetails> claim;
        try {
            claim = claimLookup.find(tx.getClaimId());
        } catch (StatusRuntimeException e) {
            return VoteResult.no("Could not look up claim " + tx.getClaimId() + " (" + e.getStatus().getCode() + ")");
        }
        if (claim.isEmpty()) {
            return VoteResult.no("Claim " + tx.getClaimId() + " not found");
        }
        String policyNo = claim.get().getPolicyNo();
        Optional<Policy> found = policies.findByPolicyNo(policyNo);
        if (found.isEmpty()) {
            return VoteResult.no("Policy " + policyNo + " not found");
        }
        Policy policy = found.get();
        tx.setPolicyId(policy.getId());
        if (policy.getStatus() != PolicyStatus.ACTIVE) {
            return VoteResult.no("Policy " + policyNo + " is " + policy.getStatus());
        }

        PolicyBalance balance = balance(policy);
        BigDecimal remaining = balance.remaining(policy.getSumInsured());
        if (remaining.compareTo(tx.getAmount()) < 0) {
            return VoteResult.no("Remaining sum insured " + rupees(remaining) + " is less than " + rupees(tx.getAmount()));
        }
        balance.reserve(tx.getAmount());
        balances.saveAndFlush(balance); // version check: a concurrent reservation makes this fail and be retried
        return VoteResult.YES;
    }

    @Override
    protected void onCommit(LimitReservation tx) {
        PolicyBalance balance = balances.findById(tx.getPolicyId()).orElseThrow();
        balance.commit(tx.getAmount());
        balances.saveAndFlush(balance);
    }

    @Override
    protected void onAbort(LimitReservation tx, ParticipantTxState previous) {
        PolicyBalance balance = balances.findById(tx.getPolicyId()).orElseThrow();
        balance.release(tx.getAmount());
        balances.saveAndFlush(balance);
    }

    /** TX_VOTE for the vote, TX_PARTICIPANT_STATE for every later change (participant = policy). */
    @Override
    protected void onTransition(LimitReservation tx, ParticipantTxState from) {
        if (tx.getClaimId() == null) return; // tombstone
        ClaimEvent event = ClaimEvent.builder(from == null ? "TX_VOTE" : "TX_PARTICIPANT_STATE", NAME, tx.getClaimId(), clock.instant())
                .tx(tx.getTxId(), null).amount(tx.getAmount())
                .participant(NAME, tx.getVote() == null ? null : tx.getVote().name(), tx.getState().name())
                .detail("from", from == null ? null : from.name())
                .detail("reason", tx.getReason())
                .detail("policyId", tx.getPolicyId())
                .build();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publish(event);
            }
        });
    }

    /** The participant's own timeouts (PREPARED → abort, PRECOMMITTED → commit). */
    @Scheduled(initialDelayString = "${claims.policy.participant.sweep-interval}",
               fixedDelayString = "${claims.policy.participant.sweep-interval}")
    public void sweep() {
        expireTimedOut();
    }

    private PolicyBalance balance(Policy policy) {
        return balances.findById(policy.getId()).orElseGet(() -> balances.saveAndFlush(new PolicyBalance(policy.getId())));
    }

    static String rupees(BigDecimal v) {
        return Rupees.format(v);
    }
}
