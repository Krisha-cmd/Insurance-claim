package com.claims.assessment.service;

import com.claims.assessment.api.dto.BreakdownLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The payout calculation. A pure function: no Spring, no I/O, no clock, so it is exactly what the
 * {@code claims.assessment.calculate} timer measures.
 *
 * <ol>
 *   <li>{@code capped = min(claimed, subLimit(category))}: no sub-limit means no cap, an uncovered category
 *       caps at 0</li>
 *   <li>{@code afterDeductible = max(capped − deductible, 0)}</li>
 *   <li>{@code copay = afterDeductible × copayPct / 100}</li>
 *   <li>{@code proposed = min(afterDeductible − copay, remainingBalance)}</li>
 * </ol>
 * Every amount is rounded to 2 decimals, HALF_UP, at the step that produces it.
 */
public final class AssessmentCalculator {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Calculation calculate(BigDecimal claimed, Coverage coverage, Balance balance, String category) {
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(balance, "balance");
        if (claimed.signum() < 0) throw new IllegalArgumentException("The claimed amount cannot be negative.");

        BigDecimal claimedAmt = money(claimed);
        BigDecimal subLimit = coverage.ruleFor(category)
                .map(r -> r.covered() ? (r.subLimit() == null ? null : money(r.subLimit())) : zero())
                .orElse(null);

        // 1. sub-limit cap
        BigDecimal afterCap = subLimit == null ? claimedAmt : claimedAmt.min(subLimit);
        // 2. deductible (the part actually deducted is at most what is left)
        BigDecimal planDeductible = nonNegative(coverage.deductible());
        BigDecimal afterDeductible = afterCap.subtract(planDeductible).max(zero());
        BigDecimal deductible = afterCap.subtract(afterDeductible);
        // 3. co-pay
        BigDecimal copayPct = nonNegative(coverage.copayPct());
        BigDecimal copay = money(afterDeductible.multiply(copayPct).divide(HUNDRED, SCALE + 4, ROUNDING));
        // 4. remaining sum insured
        BigDecimal remaining = nonNegative(balance.remaining());
        BigDecimal beforeBalanceCap = afterDeductible.subtract(copay);
        BigDecimal proposed = beforeBalanceCap.min(remaining);
        BigDecimal balanceCut = beforeBalanceCap.subtract(proposed);

        List<BreakdownLine> lines = breakdown(category, claimedAmt, subLimit, afterCap, deductible, afterDeductible,
                copayPct, copay, remaining, balanceCut, proposed);
        return new Calculation(claimedAmt, subLimit, afterCap, deductible, afterDeductible, copayPct, copay,
                remaining, balanceCut, proposed, lines);
    }

    /**
     * The breakdown shown to the adjuster. Also used to rebuild it from a stored assessment, so the labels are
     * always produced the same way.
     */
    public static List<BreakdownLine> breakdown(String category, BigDecimal claimed, BigDecimal subLimit,
                                                BigDecimal afterCap, BigDecimal deductible, BigDecimal afterDeductible,
                                                BigDecimal copayPct, BigDecimal copay, BigDecimal remaining,
                                                BigDecimal balanceCut, BigDecimal proposed) {
        String cat = category == null || category.isBlank() ? "this treatment" : category.trim();
        List<BreakdownLine> lines = new ArrayList<>();
        lines.add(new BreakdownLine("Claimed amount", claimed));
        if (subLimit == null) {
            lines.add(new BreakdownLine("No sub-limit for " + cat, afterCap));
        } else if (subLimit.signum() == 0) {
            lines.add(new BreakdownLine(cat + " is not covered by the plan", afterCap));
        } else {
            lines.add(new BreakdownLine("Capped at the " + cat + " sub-limit of " + subLimit.toPlainString(), afterCap));
        }
        lines.add(new BreakdownLine("Deductible", deductible.negate()));
        lines.add(new BreakdownLine("After deductible", afterDeductible));
        lines.add(new BreakdownLine("Co-pay (" + copayPct.stripTrailingZeros().toPlainString() + "%)", copay.negate()));
        if (balanceCut.signum() > 0) {
            lines.add(new BreakdownLine("Capped at the remaining sum insured of " + remaining.toPlainString(),
                    balanceCut.negate()));
        }
        lines.add(new BreakdownLine("Proposed amount", proposed));
        return List.copyOf(lines);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(SCALE, ROUNDING);
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return v == null ? zero() : money(v.max(BigDecimal.ZERO));
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }
}
