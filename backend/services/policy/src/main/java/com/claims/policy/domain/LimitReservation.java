package com.claims.policy.domain;

import com.claims.participant.ParticipantTx;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/** Policy's 3PC log: one reservation of a payout against a policy's balance (amount, claim, state from ParticipantTx). */
@Entity
@Table(name = "limit_reservation")
public class LimitReservation extends ParticipantTx {

    /** Set by prepare once the claim's policy is known. */
    @Column(name = "policy_id")
    private UUID policyId;

    public UUID getPolicyId() {
        return policyId;
    }

    public void setPolicyId(UUID policyId) {
        this.policyId = policyId;
    }
}
