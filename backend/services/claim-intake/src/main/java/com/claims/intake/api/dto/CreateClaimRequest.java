package com.claims.intake.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * POST /api/claims, exactly what frontend/src/pages/policyholder/FileClaimPage.jsx sends.
 * {@code patient.name} and {@code patient.relation} are ignored: Policy is the source of truth for them.
 */
public record CreateClaimRequest(
        @NotBlank(message = "Choose the policy.") @Size(max = 30) String policyNumber,
        @NotNull(message = "Choose the patient.") @Valid Patient patient,
        @NotBlank(message = "Enter the hospital name.") @Size(max = 200, message = "The hospital name is too long.") String hospital,
        @NotNull(message = "Enter the admission date.") @PastOrPresent(message = "The admission date cannot be in the future.") LocalDate admissionDate,
        @NotNull(message = "Enter the discharge date.") @PastOrPresent(message = "The discharge date cannot be in the future.") LocalDate dischargeDate,
        @NotBlank(message = "Enter the diagnosis.") @Size(max = 500, message = "The diagnosis is too long.") String diagnosis,
        @NotBlank(message = "Choose the treatment type.") @Size(max = 60) String treatmentType,
        @NotNull(message = "Enter the claimed amount.")
        @DecimalMin(value = "1", message = "The claimed amount must be at least 1.")
        @DecimalMax(value = "100000000", message = "The claimed amount is too large.")
        @Digits(integer = 12, fraction = 2, message = "Use at most 2 decimal places.") BigDecimal claimedAmount) {

    public record Patient(@NotNull(message = "Choose the patient.") UUID memberId, String name, String relation) {
    }
}
