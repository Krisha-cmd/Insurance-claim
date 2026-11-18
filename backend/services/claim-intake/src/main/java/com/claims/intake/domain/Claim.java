package com.claims.intake.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A claim. Documents, status history, eligibility checks and settlement participation reference it by
 * {@code claim_id} (no JPA collections, so nothing is loaded lazily by accident). Only
 * {@link ClaimStateMachine} changes {@link #status}.
 */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    private UUID id;

    @Column(name = "claim_no", nullable = false, unique = true)
    private String claimNo;

    @Column(name = "policy_no", nullable = false)
    private String policyNo;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    @Column(name = "member_name", nullable = false)
    private String memberName;

    @Column(name = "member_relation")
    private String memberRelation;

    @Column(name = "policyholder_id", nullable = false)
    private UUID policyholderId;

    @Column(name = "policyholder_name")
    private String policyholderName;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "hospital_name", nullable = false)
    private String hospitalName;

    @Column(name = "admission_date", nullable = false)
    private LocalDate admissionDate;

    @Column(name = "discharge_date", nullable = false)
    private LocalDate dischargeDate;

    @Column(nullable = false)
    private String diagnosis;

    @Column(name = "treatment_category", nullable = false)
    private String treatmentCategory;

    @Column(name = "claimed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal claimedAmount;

    @Column(name = "proposed_amount", precision = 14, scale = 2)
    private BigDecimal proposedAmount;

    @Column(name = "approved_amount", precision = 14, scale = 2)
    private BigDecimal approvedAmount;

    private Boolean eligible;

    @Column(name = "rejection_reason", length = 2000)
    private String rejectionReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Claim() {
    }

    /** A new claim in SUBMITTED (record its first history entry with {@link ClaimStateMachine#initial}). */
    public static Claim submit(String claimNo, String policyNo, UUID memberId, String memberName, String memberRelation,
                               UUID policyholderId, String policyholderName, UUID submittedBy, String hospitalName,
                               LocalDate admissionDate, LocalDate dischargeDate, String diagnosis,
                               String treatmentCategory, BigDecimal claimedAmount, Instant now) {
        Claim c = new Claim();
        c.id = UUID.randomUUID();
        c.claimNo = claimNo;
        c.policyNo = policyNo;
        c.memberId = memberId;
        c.memberName = memberName;
        c.memberRelation = memberRelation;
        c.policyholderId = policyholderId;
        c.policyholderName = policyholderName;
        c.submittedBy = submittedBy;
        c.hospitalName = hospitalName;
        c.admissionDate = admissionDate;
        c.dischargeDate = dischargeDate;
        c.diagnosis = diagnosis;
        c.treatmentCategory = treatmentCategory;
        c.claimedAmount = claimedAmount;
        c.status = ClaimStatus.SUBMITTED;
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    void changeStatus(ClaimStatus to, Instant now) {
        this.status = to;
        this.updatedAt = now;
    }

    public void recordEligibility(boolean eligible) {
        this.eligible = eligible;
    }

    public void recordProposedAmount(BigDecimal amount) {
        this.proposedAmount = amount;
    }

    public void recordApprovedAmount(BigDecimal amount) {
        this.approvedAmount = amount;
    }

    public void recordRejectionReason(String reason) {
        this.rejectionReason = reason;
    }

    public boolean isOwnedBy(UUID userId) {
        return submittedBy.equals(userId);
    }

    public UUID getId() { return id; }
    public String getClaimNo() { return claimNo; }
    public String getPolicyNo() { return policyNo; }
    public UUID getMemberId() { return memberId; }
    public String getMemberName() { return memberName; }
    public String getMemberRelation() { return memberRelation; }
    public UUID getPolicyholderId() { return policyholderId; }
    public String getPolicyholderName() { return policyholderName; }
    public UUID getSubmittedBy() { return submittedBy; }
    public String getHospitalName() { return hospitalName; }
    public LocalDate getAdmissionDate() { return admissionDate; }
    public LocalDate getDischargeDate() { return dischargeDate; }
    public String getDiagnosis() { return diagnosis; }
    public String getTreatmentCategory() { return treatmentCategory; }
    public BigDecimal getClaimedAmount() { return claimedAmount; }
    public BigDecimal getProposedAmount() { return proposedAmount; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public Boolean getEligible() { return eligible; }
    public String getRejectionReason() { return rejectionReason; }
    public ClaimStatus getStatus() { return status; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
