package com.claims.intake.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A claim in a list (My Claims, the adjuster queue, search). Field names follow the frontend's claim object.
 *
 * @param id          UUID used in URLs and by other services
 * @param claimNo     human-readable number, CLM-yyyy-nnnnnn
 * @param eligibility ELIGIBLE, INELIGIBLE or null (not checked yet)
 */
public record ClaimSummary(UUID id, String claimNo, String policyNumber, UUID policyholderId, String policyholderName,
                           Patient patient, String hospital, LocalDate admissionDate, LocalDate dischargeDate,
                           String diagnosis, String treatmentType, BigDecimal claimedAmount, BigDecimal proposedAmount,
                           BigDecimal approvedAmount, String eligibility, String status, String currency,
                           Instant createdAt, Instant updatedAt) {

    public record Patient(UUID memberId, String name, String relation) {
    }
}
