package com.claims.eligibility.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code PUT /api/eligibility/{claimId}/override}: the adjuster's verdict, a reason from the list, and a justification. */
public record OverrideRequest(
        @NotNull(message = "eligible is required") Boolean eligible,
        @NotBlank(message = "reason is required") @Size(max = 200, message = "reason must be at most 200 characters") String reason,
        @NotBlank(message = "comment is required")
        @Size(min = 10, max = 2000, message = "comment must be 10 to 2000 characters") String comment) {
}
