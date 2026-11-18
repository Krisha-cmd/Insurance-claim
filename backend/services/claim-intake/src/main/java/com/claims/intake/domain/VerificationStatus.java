package com.claims.intake.domain;

public enum VerificationStatus {
    /** Upload URL issued; waiting for the verification Lambda's result. */
    PENDING,
    VERIFIED,
    REJECTED
}
