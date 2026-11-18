package com.claims.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One 3PC settlement attempt. The coordinator's log record. */
@Entity
@Table(name = "settlement_tx")
public class SettlementTx {

    @Id
    @Column(name = "tx_id")
    private UUID txId;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CoordinatorState state;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version; // null until first saved, so Spring Data persists instead of merging

    protected SettlementTx() {
    }

    public static SettlementTx begin(UUID claimId, BigDecimal amount, int attempt, Instant now) {
        SettlementTx tx = new SettlementTx();
        tx.txId = UUID.randomUUID();
        tx.claimId = claimId;
        tx.amount = amount;
        tx.attempt = attempt;
        tx.state = CoordinatorState.WAITING_VOTES;
        tx.createdAt = now;
        tx.updatedAt = now;
        return tx;
    }

    public void moveTo(CoordinatorState next, Instant now) {
        if (state.isFinal()) {
            throw new IllegalStateException("Settlement " + txId + " is already " + state);
        }
        this.state = next;
        this.updatedAt = now;
    }

    public UUID getTxId() { return txId; }
    public UUID getClaimId() { return claimId; }
    public BigDecimal getAmount() { return amount; }
    public CoordinatorState getState() { return state; }
    public int getAttempt() { return attempt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
