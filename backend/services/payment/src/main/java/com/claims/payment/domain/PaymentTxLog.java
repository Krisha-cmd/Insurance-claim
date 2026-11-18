package com.claims.payment.domain;

import com.claims.participant.ParticipantTx;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** Payment's 3PC log: one row per settlement tx, pointing at the payment it created. */
@Entity
@Table(name = "payment_tx_log")
public class PaymentTxLog extends ParticipantTx {

    @Column(name = "payment_id")
    private UUID paymentId;

    public UUID getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(UUID paymentId) {
        this.paymentId = paymentId;
    }
}
