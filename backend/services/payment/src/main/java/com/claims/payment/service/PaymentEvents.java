package com.claims.payment.service;

import com.claims.common.event.ClaimEvent;
import com.claims.common.event.EventPublisher;
import com.claims.payment.domain.Payment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** Payment events on claim-events, sent after the transaction commits. */
@Component
public class PaymentEvents {

    public static final String SERVICE = "payment";

    private final EventPublisher publisher;
    private final Clock clock;

    public PaymentEvents(EventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /** PAYMENT_AUTHORIZED, PAYMENT_CAPTURED (with the payment reference), PAYMENT_VOIDED, PAYMENT_FAILED, PAYMENT_CAPTURE_PENDING. */
    public void payment(String type, Payment p, UUID txId, String note) {
        ClaimEvent.Builder b = ClaimEvent.builder(type, SERVICE, p.getClaimId(), clock.instant())
                .amount(p.getAmount()).tx(txId, null)
                .detail("paymentId", p.getId())
                .detail("status", p.getStatus().name())
                .detail("authId", p.getAuthId());
        if (p.getGatewayRef() != null) b.detail("paymentReference", p.getGatewayRef());
        if (note != null) b.detail("note", note);
        send(b.build());
    }

    /** This participant's 3PC row changed (participant = payment). */
    public void participant(String type, UUID claimId, UUID txId, String vote, String state, Map<String, Object> change) {
        send(ClaimEvent.builder(type, SERVICE, claimId, clock.instant()).tx(txId, null)
                .participant(SERVICE, vote, state).change(change).build());
    }

    private void send(ClaimEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publish(event);
                }
            });
        } else {
            publisher.publish(event);
        }
    }
}
