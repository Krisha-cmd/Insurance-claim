package com.claims.intake.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/claims/{id}: the claim with its documents, latest eligibility result and status history.
 *
 * @param paymentReference always null here; the payment reference lives in the Payment service
 */
public record ClaimResponse(UUID id, String claimNo, String policyNumber, UUID policyholderId, String policyholderName,
                            ClaimSummary.Patient patient, String hospital, LocalDate admissionDate,
                            LocalDate dischargeDate, String diagnosis, String treatmentType, BigDecimal claimedAmount,
                            BigDecimal proposedAmount, BigDecimal approvedAmount, String eligibility, String status,
                            String currency, String rejectionReason, String paymentReference,
                            List<DocumentResponse> documents, EligibilityView eligibilityCheck,
                            List<StatusChange> statusHistory, Instant createdAt, Instant updatedAt) {

    public record EligibilityView(boolean eligible, List<Rule> checks, Instant evaluatedAt, boolean overridden,
                                  String overrideComment) {
    }

    public record Rule(String rule, String label, boolean passed, String detail) {
    }

    public record StatusChange(String fromStatus, String toStatus, String actor, UUID changedBy, String comment,
                               Instant changedAt) {
    }
}
