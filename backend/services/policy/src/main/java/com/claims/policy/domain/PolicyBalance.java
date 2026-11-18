package com.claims.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * How much of a policy's sum insured is used and reserved. Settlements change it with optimistic locking
 * ({@link #version}): two concurrent reservations cannot both succeed against a stale balance.
 */
@Entity
@Table(name = "policy_balance")
public class PolicyBalance {

    @Id
    @Column(name = "policy_id")
    private UUID policyId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal utilised;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal reserved;

    @Version
    private Long version;

    protected PolicyBalance() {
    }

    public PolicyBalance(UUID policyId) {
        this.policyId = policyId;
        this.utilised = BigDecimal.ZERO;
        this.reserved = BigDecimal.ZERO;
    }

    /** What can still be paid out: sum insured − utilised − reserved. */
    public BigDecimal remaining(BigDecimal sumInsured) {
        return sumInsured.subtract(utilised).subtract(reserved);
    }

    /** prepare: hold {@code amount} for a settlement. */
    public void reserve(BigDecimal amount) {
        reserved = reserved.add(amount);
    }

    /** commit: the held amount is paid. */
    public void commit(BigDecimal amount) {
        reserved = nonNegative(reserved.subtract(amount));
        utilised = utilised.add(amount);
    }

    /** abort: give the held amount back. */
    public void release(BigDecimal amount) {
        reserved = nonNegative(reserved.subtract(amount));
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        if (v.signum() < 0) {
            throw new IllegalStateException("Reserved amount would become negative");
        }
        return v;
    }

    public UUID getPolicyId() { return policyId; }
    public BigDecimal getUtilised() { return utilised; }
    public BigDecimal getReserved() { return reserved; }
    public Long getVersion() { return version; }
}
