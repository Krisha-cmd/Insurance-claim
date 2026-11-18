package com.claims.ops.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * An audit event in the frontend's shape ({@code id, claimId, type, actor, at, before, after, comment}, read by
 * frontend/src/lib/auditText.js). {@code type} uses the frontend's names (CLAIM_SUBMITTED, STATUS_CHANGED,
 * SETTLEMENT_COMMITTED …); {@code eventType} is the name the service emitted. The remaining fields are the raw
 * envelope, for the detail views.
 */
public record AuditEventResponse(String id, String claimId, String type, String eventType, String service, Actor actor,
                                 Instant at, Map<String, Object> before, Map<String, Object> after, String comment,
                                 String policyNo, BigDecimal amount, String txId, String phase, String participant,
                                 String vote, String participantState) {

    public record Actor(String id, String name, String role) {
    }
}
