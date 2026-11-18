package com.claims.ops.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * A policy with more than 2 new claims in a 10-minute window (from the Flink job).
 *
 * @param firstClaimAt the start of the flagging window (the job stores the window, not each claim's time)
 * @param lastClaimAt  the end of the flagging window
 * @param holderName   not in the analytics table; always null for now
 */
public record FraudFlagResponse(String policyNumber, String holderName, int claimCount, Instant firstClaimAt,
                                Instant lastClaimAt, List<String> claimIds, int windowMinutes) {
}
