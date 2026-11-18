package com.claims.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approval_decision")
public class ApprovalDecision {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false, unique = true)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    /** The approved (payable) amount; null when rejected. */
    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 2000)
    private String comment;

    @Column(name = "adjuster_id", nullable = false)
    private UUID adjusterId;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected ApprovalDecision() {
    }

    public ApprovalDecision(UUID claimId, Decision decision, BigDecimal amount, String comment,
                            UUID adjusterId, Instant decidedAt) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.decision = decision;
        this.amount = amount;
        this.comment = comment;
        this.adjusterId = adjusterId;
        this.decidedAt = decidedAt;
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public Decision getDecision() { return decision; }
    public BigDecimal getAmount() { return amount; }
    public String getComment() { return comment; }
    public UUID getAdjusterId() { return adjusterId; }
    public Instant getDecidedAt() { return decidedAt; }
}
