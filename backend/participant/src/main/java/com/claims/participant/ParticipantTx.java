package com.claims.participant;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Base of each participant's tx-log row (settlement_participation, limit_reservation, payment_tx_log …).
 * Columns: tx_id (PK), claim_id, amount, attempt, state, vote, reason, created_at, updated_at, version.
 * Only {@link ThreePhaseParticipant} changes the protocol fields.
 */
@MappedSuperclass
public abstract class ParticipantTx {

    public enum Vote { YES, NO }

    @Id
    @Column(name = "tx_id")
    private UUID txId;

    /** Null only for a tombstone (Abort that arrived before CanCommit). */
    @Column(name = "claim_id")
    private UUID claimId;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParticipantTxState state;

    @Enumerated(EnumType.STRING)
    private Vote vote;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    void init(UUID txId, UUID claimId, BigDecimal amount, int attempt, Instant now) {
        this.txId = txId;
        this.claimId = claimId;
        this.amount = amount;
        this.attempt = attempt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void votedYes(Instant now) {
        this.vote = Vote.YES;
        move(ParticipantTxState.PREPARED, null, now);
    }

    void votedNo(String reason, Instant now) {
        this.vote = Vote.NO;
        move(ParticipantTxState.ABORTED, reason, now);
    }

    void move(ParticipantTxState next, String reason, Instant now) {
        this.state = next;
        if (reason != null) this.reason = reason.length() <= 500 ? reason : reason.substring(0, 497) + "...";
        this.updatedAt = now;
    }

    public UUID getTxId() { return txId; }
    public UUID getClaimId() { return claimId; }
    public BigDecimal getAmount() { return amount; }
    public int getAttempt() { return attempt; }
    public ParticipantTxState getState() { return state; }
    public Vote getVote() { return vote; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
