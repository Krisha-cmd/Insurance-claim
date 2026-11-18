package com.claims.ops.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * GET /api/analytics/summary (the KPI tiles). The latest 1-minute window, plus totals over the last
 * {@code lookbackMinutes}. {@code approvalRate} is approved ÷ (approved + rejected), null if nothing was decided.
 */
public record SummaryResponse(Instant windowStart, Instant windowEnd, long claimsLastMinute, Double approvalRate,
                              BigDecimal avgClaimedAmount, long settlementAborts, long settlementCommits,
                              int lookbackMinutes, Instant generatedAt) {
}
