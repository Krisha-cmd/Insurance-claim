package com.claims.assessment.service;

import com.claims.assessment.api.dto.BreakdownLine;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of {@link AssessmentCalculator#calculate}: every intermediate amount (stored one column each) and
 * the breakdown built from them. All amounts have scale 2.
 *
 * @param subLimit        the sub-limit applied, or null if the category has none
 * @param deductible      the part of the plan deductible actually deducted (never more than {@code afterCap})
 * @param balanceCut      how much the remaining-sum-insured cap removed (0 when it did not bite)
 */
public record Calculation(BigDecimal claimed, BigDecimal subLimit, BigDecimal afterCap, BigDecimal deductible,
                         BigDecimal afterDeductible, BigDecimal copayPct, BigDecimal copayAmount,
                         BigDecimal remainingBalance, BigDecimal balanceCut, BigDecimal proposed,
                         List<BreakdownLine> lines) {
}
