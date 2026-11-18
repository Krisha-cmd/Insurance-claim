package com.claims.assessment.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** {@code PUT /api/assessments/{claimId}/adjust}. "At most the claimed amount" is checked in the service. */
public record AdjustRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0", inclusive = false, message = "amount must be more than 0")
        @Digits(integer = 12, fraction = 2, message = "amount must have at most 2 decimals")
        BigDecimal amount,
        @NotBlank(message = "comment is required")
        @Size(min = 10, max = 2000, message = "comment must be 10 to 2000 characters")
        String comment) {
}
