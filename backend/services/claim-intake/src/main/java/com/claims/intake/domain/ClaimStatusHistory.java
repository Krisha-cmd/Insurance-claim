package com.claims.intake.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One status change. Written only by {@link ClaimStateMachine}. */
@Entity
@Table(name = "claim_status_history")
public class ClaimStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ClaimStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private ClaimStatus toStatus;

    @Column(name = "changed_by")
    private UUID changedBy;

    @Column(nullable = false)
    private String actor;

    @Column(length = 2000)
    private String comment;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected ClaimStatusHistory() {
    }

    ClaimStatusHistory(UUID claimId, ClaimStatus from, ClaimStatus to, Actor actor, String comment, Instant at) {
        this.claimId = claimId;
        this.fromStatus = from;
        this.toStatus = to;
        this.changedBy = actor.id();
        this.actor = actor.label();
        this.comment = comment;
        this.changedAt = at;
    }

    public Long getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public ClaimStatus getFromStatus() { return fromStatus; }
    public ClaimStatus getToStatus() { return toStatus; }
    public UUID getChangedBy() { return changedBy; }
    public String getActor() { return actor; }
    public String getComment() { return comment; }
    public Instant getChangedAt() { return changedAt; }
}
