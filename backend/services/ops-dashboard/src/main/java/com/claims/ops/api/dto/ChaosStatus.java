package com.claims.ops.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** GET/POST /api/ops/chaos: {PAYMENT: {...}, COORDINATOR: {...}}, as the frontend's chaos page reads it. */
public record ChaosStatus(@JsonProperty("PAYMENT") Target payment, @JsonProperty("COORDINATOR") Target coordinator) {

    /**
     * @param mode            the frontend's name: OFF, FAIL, CRASH_AFTER_PRECOMMIT … (UNKNOWN if unreachable)
     * @param serviceMode     what the service reports (NONE, FAIL_AUTHORIZE, …)
     * @param changedAt       the last change made through this service (null after a restart)
     * @param affectedClaimId not tracked yet; always null
     * @param reachable       false if the service did not answer
     */
    public record Target(String mode, String serviceMode, Long delayMs, Instant changedAt, AuditEventResponse.Actor changedBy,
                         String affectedClaimId, boolean reachable, String error) {
    }
}
