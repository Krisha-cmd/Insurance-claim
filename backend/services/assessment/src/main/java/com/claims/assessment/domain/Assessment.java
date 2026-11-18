package com.claims.assessment.domain;

import com.claims.assessment.service.Calculation;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** One claim's assessment: every step of the calculation, the final amount, and the adjuster's adjustments. */
@Entity
@Table(name = "assessment")
public class Assessment {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false, unique = true)
    private UUID claimId;

    @Column(name = "policy_no", nullable = false)
    private String policyNo;

    @Column(name = "treatment_category", nullable = false)
    private String treatmentCategory;

    @Column(name = "policyholder_id")
    private UUID policyholderId;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "claimed_amount", nullable = false)
    private BigDecimal claimedAmount;

    @Column(name = "sub_limit_applied")
    private BigDecimal subLimitApplied;

    @Column(name = "after_cap", nullable = false)
    private BigDecimal afterCap;

    @Column(name = "deductible", nullable = false)
    private BigDecimal deductible;

    @Column(name = "after_deductible", nullable = false)
    private BigDecimal afterDeductible;

    @Column(name = "copay_pct", nullable = false)
    private BigDecimal copayPct;

    @Column(name = "copay_amount", nullable = false)
    private BigDecimal copayAmount;

    @Column(name = "remaining_balance", nullable = false)
    private BigDecimal remainingBalance;

    @Column(name = "proposed_amount", nullable = false)
    private BigDecimal proposedAmount;

    @Column(name = "final_amount", nullable = false)
    private BigDecimal finalAmount;

    @Column(name = "adjusted", nullable = false)
    private boolean adjusted;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "assessment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("adjustedAt ASC")
    private List<AssessmentAdjustment> adjustments = new ArrayList<>();

    protected Assessment() {
    }

    /** The claim facts the assessment keeps (from Claim Intake's ClaimDetails). */
    public record ClaimFacts(UUID claimId, String policyNo, String treatmentCategory, UUID policyholderId,
                             UUID submittedBy) {
    }

    public static Assessment create(ClaimFacts claim, Calculation calc, String currency, Instant now) {
        Assessment a = new Assessment();
        a.id = UUID.randomUUID();
        a.claimId = claim.claimId();
        a.currency = currency;
        a.createdAt = now;
        a.apply(claim, calc, now);
        return a;
    }

    /** A fresh calculation. Only before any adjustment: an adjusted amount is the adjuster's decision. */
    public void recalculate(ClaimFacts claim, Calculation calc, Instant now) {
        if (adjusted) throw new IllegalStateException("Assessment " + id + " was adjusted; it is not recalculated");
        apply(claim, calc, now);
    }

    private void apply(ClaimFacts claim, Calculation calc, Instant now) {
        policyNo = claim.policyNo();
        treatmentCategory = claim.treatmentCategory();
        policyholderId = claim.policyholderId();
        submittedBy = claim.submittedBy();
        claimedAmount = calc.claimed();
        subLimitApplied = calc.subLimit();
        afterCap = calc.afterCap();
        deductible = calc.deductible();
        afterDeductible = calc.afterDeductible();
        copayPct = calc.copayPct();
        copayAmount = calc.copayAmount();
        remainingBalance = calc.remainingBalance();
        proposedAmount = calc.proposed();
        finalAmount = calc.proposed();
        updatedAt = now;
    }

    /** Sets the final amount; the calculation itself stays as it was. */
    public AssessmentAdjustment adjust(BigDecimal amount, String comment, UUID adjusterId, String adjusterName, Instant now) {
        AssessmentAdjustment adj = new AssessmentAdjustment(this, finalAmount, amount, comment, adjusterId, adjusterName, now);
        adjustments.add(adj);
        finalAmount = amount;
        adjusted = true;
        updatedAt = now;
        return adj;
    }

    public boolean isOwnedBy(UUID userId, UUID userPolicyholderId) {
        return (userId != null && userId.equals(submittedBy))
                || (userPolicyholderId != null && userPolicyholderId.equals(policyholderId));
    }

    /** What the remaining-sum-insured cap removed (0 if it did not apply). */
    public BigDecimal balanceCut() {
        return afterDeductible.subtract(copayAmount).subtract(proposedAmount);
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public String getPolicyNo() { return policyNo; }
    public String getTreatmentCategory() { return treatmentCategory; }
    public UUID getPolicyholderId() { return policyholderId; }
    public UUID getSubmittedBy() { return submittedBy; }
    public BigDecimal getClaimedAmount() { return claimedAmount; }
    public BigDecimal getSubLimitApplied() { return subLimitApplied; }
    public BigDecimal getAfterCap() { return afterCap; }
    public BigDecimal getDeductible() { return deductible; }
    public BigDecimal getAfterDeductible() { return afterDeductible; }
    public BigDecimal getCopayPct() { return copayPct; }
    public BigDecimal getCopayAmount() { return copayAmount; }
    public BigDecimal getRemainingBalance() { return remainingBalance; }
    public BigDecimal getProposedAmount() { return proposedAmount; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public boolean isAdjusted() { return adjusted; }
    public String getCurrency() { return currency; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AssessmentAdjustment> getAdjustments() { return Collections.unmodifiableList(adjustments); }
}
