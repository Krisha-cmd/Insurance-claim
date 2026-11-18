package com.claims.payment.service;

import com.claims.participant.ParticipantTimeouts;
import com.claims.participant.ParticipantTxState;
import com.claims.participant.ThreePhaseParticipant;
import com.claims.participant.VoteResult;
import com.claims.payment.config.PaymentProperties;
import com.claims.payment.domain.PayeeAccount;
import com.claims.payment.domain.Payment;
import com.claims.payment.domain.PaymentStatus;
import com.claims.payment.domain.PaymentTxLog;
import com.claims.payment.gateway.GatewayException;
import com.claims.payment.gateway.GatewayResponse;
import com.claims.payment.grpc.ClaimLookupClient;
import com.claims.payment.repository.PayeeAccountRepository;
import com.claims.payment.repository.PaymentRepository;
import com.claims.payment.repository.PaymentTxLogRepository;
import com.claims.proto.claim.v1.ClaimDetails;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment's side of the settlement 3PC: the money, through the external gateway.
 * <ul>
 *   <li>prepare: find the claimant's payee account (via ClaimService.GetClaim → submitted_by), refuse a claim
 *       that is already paid or being paid, then <b>authorize</b> (3 s timeout). YES with the authId stored;
 *       a failed or slow gateway votes NO and the payment is FAILED.</li>
 *   <li>commit: <b>capture</b>, up to 3 tries with backoff. The decision is already commit, so it must
 *       eventually capture: if every try fails the payment stays AUTHORIZED, an ERROR is logged and
 *       {@link PaymentRecoverySweep} keeps trying.</li>
 *   <li>abort: <b>void</b> an authorized payment (the sweep retries a void that failed).</li>
 * </ul>
 * Every gateway call is recorded in payment_attempt. Gateway calls are idempotent (paymentId / authId), so a
 * repeated phase is safe.
 */
@Component
public class PaymentParticipant extends ThreePhaseParticipant<PaymentTxLog> {

    private static final Logger log = LoggerFactory.getLogger(PaymentParticipant.class);
    public static final String NAME = "payment";

    private final ClaimLookupClient claims;
    private final PayeeAccountRepository payees;
    private final PaymentRepository payments;
    private final GatewayCalls gateway;
    private final PaymentEvents events;
    private final Clock clock;
    private final String currency;

    public PaymentParticipant(PaymentTxLogRepository store, TransactionTemplate tx, Clock clock, PaymentProperties props,
                              ClaimLookupClient claims, PayeeAccountRepository payees, PaymentRepository payments,
                              GatewayCalls gateway, PaymentEvents events) {
        super(NAME, store, tx, clock,
                new ParticipantTimeouts(props.participant().prepareTimeout(), props.participant().precommitTimeout()));
        this.claims = claims;
        this.payees = payees;
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.clock = clock;
        this.currency = props.currency();
    }

    @Override
    protected PaymentTxLog newTx() {
        return new PaymentTxLog();
    }

    @Override
    protected VoteResult prepare(PaymentTxLog tx) {
        if (tx.getAmount() == null || tx.getAmount().signum() <= 0) {
            return VoteResult.no("Settlement amount must be positive");
        }
        Optional<ClaimDetails> claim;
        try {
            claim = claims.find(tx.getClaimId());
        } catch (StatusRuntimeException e) {
            return VoteResult.no("Could not look up claim " + tx.getClaimId() + " (" + e.getStatus().getCode() + ")");
        }
        if (claim.isEmpty()) {
            return VoteResult.no("Claim " + tx.getClaimId() + " not found");
        }
        UUID claimant;
        try {
            claimant = UUID.fromString(claim.get().getSubmittedBy());
        } catch (IllegalArgumentException e) {
            return VoteResult.no("Claim " + tx.getClaimId() + " has no claimant");
        }
        List<PayeeAccount> accounts = payees.findByUserIdOrderByVerifiedDesc(claimant);
        if (accounts.isEmpty()) {
            return VoteResult.no("No payee account on file for the policyholder");
        }
        PayeeAccount payee = accounts.getFirst();
        if (!payee.isVerified()) {
            return VoteResult.no("The payee account " + payee.getAccountNoMasked() + " is not verified");
        }
        if (payments.existsByClaimIdAndStatusIn(tx.getClaimId(), EnumSet.of(PaymentStatus.CAPTURED))) {
            return VoteResult.no("Claim " + tx.getClaimId() + " has already been paid");
        }
        if (payments.existsByClaimIdAndStatusIn(tx.getClaimId(), EnumSet.of(PaymentStatus.AUTHORIZED))) {
            return VoteResult.no("Another payment for claim " + tx.getClaimId() + " is in progress");
        }

        Payment payment = Payment.start(tx.getClaimId(), payee.getId(), tx.getAmount(), currency, clock.instant());
        payments.saveAndFlush(payment); // the attempt rows reference it
        tx.setPaymentId(payment.getId());
        try {
            GatewayResponse auth = gateway.authorize(payment);
            payment.authorized(auth.reference(), clock.instant());
            payments.save(payment);
            events.payment("PAYMENT_AUTHORIZED", payment, tx.getTxId(), null);
            return VoteResult.YES;
        } catch (GatewayException e) {
            payment.failed(clock.instant());
            payments.save(payment);
            events.payment("PAYMENT_FAILED", payment, tx.getTxId(), e.getMessage());
            return VoteResult.no("Payment gateway: " + e.getMessage() + " (" + e.code() + ")");
        }
    }

    @Override
    protected void onPreCommit(PaymentTxLog tx) {
        // Nothing external: the money stays authorized until DoCommit.
    }

    @Override
    protected void onCommit(PaymentTxLog tx) {
        Payment payment = payments.findById(tx.getPaymentId()).orElseThrow();
        capture(payment, tx.getTxId(), true);
    }

    @Override
    protected void onAbort(PaymentTxLog tx, ParticipantTxState previous) {
        if (tx.getPaymentId() == null) return;
        Payment payment = payments.findById(tx.getPaymentId()).orElseThrow();
        voidPayment(payment, tx.getTxId());
    }

    /** @param withRetry true in DoCommit (3 tries with backoff), false in the sweep (one try per sweep) */
    void capture(Payment payment, UUID txId, boolean withRetry) {
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            return; // CAPTURED already (a repeated DoCommit), or nothing to capture
        }
        Optional<GatewayResponse> captured;
        if (withRetry) {
            captured = gateway.captureWithRetry(payment);
        } else {
            try {
                captured = Optional.of(gateway.captureOnce(payment));
            } catch (GatewayException e) {
                captured = Optional.empty();
            }
        }
        if (captured.isPresent()) {
            payment.captured(captured.get().reference(), clock.instant());
            payments.save(payment);
            events.payment("PAYMENT_CAPTURED", payment, txId, null);
            log.info("Payment {} for claim {} CAPTURED ({})", payment.getId(), payment.getClaimId(), payment.getGatewayRef());
        } else {
            // The settlement is committed and cannot be undone: the money must still move. Keep it AUTHORIZED.
            log.error("CAPTURE PENDING: payment {} for claim {} (amount {}) is committed but not captured; "
                    + "the recovery sweep will keep trying", payment.getId(), payment.getClaimId(), payment.getAmount());
            events.payment("PAYMENT_CAPTURE_PENDING", payment, txId, "capture failed; retried by the recovery sweep");
        }
    }

    void voidPayment(Payment payment, UUID txId) {
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            return; // FAILED (never authorized) or VOIDED already
        }
        try {
            gateway.voidAuth(payment);
            payment.voided(clock.instant());
            payments.save(payment);
            events.payment("PAYMENT_VOIDED", payment, txId, null);
        } catch (GatewayException e) {
            log.warn("Void of payment {} failed ({}); the recovery sweep will retry", payment.getId(), e.code());
        }
    }

    /** TX_VOTE for the vote, TX_PARTICIPANT_STATE for every later change. */
    @Override
    protected void onTransition(PaymentTxLog tx, ParticipantTxState from) {
        if (tx.getClaimId() == null) return; // tombstone
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from == null ? null : from.name());
        change.put("reason", tx.getReason());
        change.put("paymentId", tx.getPaymentId());
        events.participant(from == null ? "TX_VOTE" : "TX_PARTICIPANT_STATE", tx.getClaimId(), tx.getTxId(),
                tx.getVote() == null ? null : tx.getVote().name(), tx.getState().name(), change);
    }

    /** The participant's own timeouts (PREPARED → abort, PRECOMMITTED → commit). */
    @Scheduled(initialDelayString = "${claims.payment.participant.sweep-interval}",
               fixedDelayString = "${claims.payment.participant.sweep-interval}")
    public void sweep() {
        expireTimedOut();
    }
}
