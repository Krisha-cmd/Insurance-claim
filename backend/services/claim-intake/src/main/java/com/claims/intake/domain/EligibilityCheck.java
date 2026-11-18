package com.claims.intake.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** One eligibility result: automatic (from the workflow) or an adjuster override (via RecordEligibility). */
@Entity
@Table(name = "eligibility_check")
public class EligibilityCheck {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(nullable = false)
    private boolean eligible;

    /** JSON array of {rule, label, passed, detail}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_results", nullable = false, columnDefinition = "jsonb")
    private String ruleResultsJson;

    @Column(nullable = false)
    private boolean overridden;

    @Column(name = "override_comment", length = 2000)
    private String overrideComment;

    @Column(name = "checked_by", nullable = false)
    private String checkedBy;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    protected EligibilityCheck() {
    }

    public EligibilityCheck(UUID claimId, boolean eligible, String ruleResultsJson, boolean overridden,
                            String overrideComment, Actor by, Instant at) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.eligible = eligible;
        this.ruleResultsJson = ruleResultsJson;
        this.overridden = overridden;
        this.overrideComment = overrideComment;
        this.checkedBy = by.label();
        this.checkedAt = at;
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public boolean isEligible() { return eligible; }
    public String getRuleResultsJson() { return ruleResultsJson; }
    public boolean isOverridden() { return overridden; }
    public String getOverrideComment() { return overrideComment; }
    public String getCheckedBy() { return checkedBy; }
    public Instant getCheckedAt() { return checkedAt; }
}
