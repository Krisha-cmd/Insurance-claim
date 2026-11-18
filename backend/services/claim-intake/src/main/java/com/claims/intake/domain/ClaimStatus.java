package com.claims.intake.domain;

/** Same values as CLAIM_STATUS in frontend/src/lib/status.js and ClaimStatus in claim.proto. */
public enum ClaimStatus {
    SUBMITTED,
    DOCS_PENDING,
    DOCS_VERIFIED,
    ELIGIBILITY_CHECKED,
    ASSESSED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    SETTLING,
    SETTLED,
    SETTLEMENT_FAILED
}
