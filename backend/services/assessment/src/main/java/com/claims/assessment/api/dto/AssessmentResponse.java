package com.claims.assessment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/assessments/{claimId}}, in the shape the adjuster's Assessment tab reads
 * (frontend/src/pages/adjuster/review/AssessmentTab.jsx and the mock in frontend/src/api/mocks).
 *
 * @param calculatedAmount what the calculation produced
 * @param proposedAmount   the amount in force: the latest adjustment, else {@code calculatedAmount}
 * @param finalAmount      same as {@code proposedAmount}; what Approval settles (GetFinalAmount)
 * @param subLimit         the sub-limit applied, or null if the category has none
 * @param lines            the step-by-step breakdown
 */
public record AssessmentResponse(
        UUID claimId,
        String policyNumber,
        String treatmentCategory,
        BigDecimal claimedAmount,
        BigDecimal subLimit,
        BigDecimal cappedAmount,
        BigDecimal deductible,
        BigDecimal afterDeductible,
        BigDecimal coPayPercent,
        BigDecimal coPayAmount,
        BigDecimal remainingBalance,
        BigDecimal calculatedAmount,
        BigDecimal proposedAmount,
        BigDecimal finalAmount,
        boolean adjusted,
        String currency,
        List<BreakdownLine> lines,
        List<Adjustment> adjustments,
        Instant assessedAt,
        Instant updatedAt) {

    /** {@code from → to} by an adjuster, with the comment. */
    public record Adjustment(BigDecimal from, BigDecimal to, String comment, Actor by, Instant at) {
    }

    public record Actor(UUID id, String name, String role) {
    }
}
