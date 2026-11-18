package com.claims.approval.api.dto;

import com.claims.approval.domain.Decision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /api/approvals/{claimId} → {decision: 'APPROVED'|'REJECTED', comment} */
public record DecisionRequest(
        @NotNull(message = "Decision must be APPROVED or REJECTED.") Decision decision,
        @NotNull(message = "A justification comment of at least 10 characters is required.")
        @Pattern(regexp = "(?s)\\s*\\S.{8,}\\S\\s*", message = "A justification comment of at least 10 characters is required.")
        @Size(max = 2000, message = "The comment can be at most 2000 characters.")
        String comment) {
}
