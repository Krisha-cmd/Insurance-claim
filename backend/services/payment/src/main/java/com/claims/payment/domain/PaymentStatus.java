package com.claims.payment.domain;

/** AUTHORIZED (held at the gateway) → CAPTURED (paid) or VOIDED (released); FAILED if authorization failed. */
public enum PaymentStatus {
    AUTHORIZED,
    CAPTURED,
    VOIDED,
    FAILED
}
