package com.claims.assessment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One manual change of the final amount by an adjuster, with the required justification. */
@Entity
@Table(name = "assessment_adjustment")
public class AssessmentAdjustment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessment_id", nullable = false)
    private Assessment assessment;

    @Column(name = "before_amount", nullable = false)
    private BigDecimal beforeAmount;

    @Column(name = "after_amount", nullable = false)
    private BigDecimal afterAmount;

    @Column(name = "comment", nullable = false)
    private String comment;

    @Column(name = "adjuster_id", nullable = false)
    private UUID adjusterId;

    @Column(name = "adjuster_name")
    private String adjusterName;

    @Column(name = "adjusted_at", nullable = false)
    private Instant adjustedAt;

    protected AssessmentAdjustment() {
    }

    AssessmentAdjustment(Assessment assessment, BigDecimal beforeAmount, BigDecimal afterAmount, String comment,
                         UUID adjusterId, String adjusterName, Instant adjustedAt) {
        this.id = UUID.randomUUID();
        this.assessment = assessment;
        this.beforeAmount = beforeAmount;
        this.afterAmount = afterAmount;
        this.comment = comment;
        this.adjusterId = adjusterId;
        this.adjusterName = adjusterName;
        this.adjustedAt = adjustedAt;
    }

    public UUID getId() { return id; }
    public BigDecimal getBeforeAmount() { return beforeAmount; }
    public BigDecimal getAfterAmount() { return afterAmount; }
    public String getComment() { return comment; }
    public UUID getAdjusterId() { return adjusterId; }
    public String getAdjusterName() { return adjusterName; }
    public Instant getAdjustedAt() { return adjustedAt; }
}
