package com.claims.eligibility.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/eligibility/{claimId}}, in the shape the adjuster's Eligibility tab reads
 * (frontend/src/pages/adjuster/review/EligibilityTab.jsx and the mock in frontend/src/api/mocks).
 *
 * @param eligible      the verdict in force: the latest override if there is one, else {@code rulesEligible}
 * @param rulesEligible what the rules say now (every check passed)
 * @param checks        every rule's result, in order; all of them, even after the first failure
 * @param override      the latest override recorded in Claim Intake, or null
 */
public record EligibilityResponse(UUID claimId, boolean eligible, boolean rulesEligible, List<RuleResultDto> checks,
                                  Instant evaluatedAt, boolean overridden, Override override) {

    public record Override(boolean eligible, String reason, String comment, By by, Instant at) {
    }

    /** Who overrode it, e.g. {name: "Rohan Mehta", role: "ADJUSTER"}. */
    public record By(String name, String role) {
    }
}
