package com.claims.intake.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "claim_document")
public class ClaimDocument {

    @Id
    private UUID id;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false)
    private DocType docType;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "s3_key", nullable = false, unique = true, length = 500)
    private String s3Key;

    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private VerificationStatus verificationStatus;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "superseded_by")
    private UUID supersededBy;

    protected ClaimDocument() {
    }

    public ClaimDocument(UUID id, UUID claimId, DocType docType, String fileName, String contentType, Long sizeBytes,
                         String s3Key, Instant now) {
        this.id = id;
        this.claimId = claimId;
        this.docType = docType;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.s3Key = s3Key;
        this.verificationStatus = VerificationStatus.PENDING;
        this.uploadedAt = now;
    }

    /** Applies the verifier's result. Only a PENDING document changes. */
    public void applyResult(VerificationStatus result, String reason, String checksum, Instant at) {
        if (verificationStatus != VerificationStatus.PENDING) {
            throw new IllegalStateException("Document " + id + " is already " + verificationStatus);
        }
        this.verificationStatus = result;
        this.rejectionReason = result == VerificationStatus.REJECTED ? truncate(reason) : null;
        this.checksum = checksum;
        this.verifiedAt = at;
    }

    public void supersedeBy(UUID newer) {
        this.supersededBy = newer;
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank()) return "Rejected by document verification";
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public DocType getDocType() { return docType; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getS3Key() { return s3Key; }
    public String getChecksum() { return checksum; }
    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public UUID getSupersededBy() { return supersededBy; }
    public boolean isCurrent() { return supersededBy == null; }
}
