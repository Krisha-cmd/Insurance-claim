package com.claims.ops.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/ops/chaos.
 * <ul>
 *   <li>PAYMENT: FAIL | OFF (the frontend's names), or the gateway's own NONE | FAIL_AUTHORIZE | FAIL_CAPTURE | SLOW
 *       (SLOW uses {@code delayMs}).</li>
 *   <li>COORDINATOR: CRASH_AFTER_PRECOMMIT | CRASH_BEFORE_PRECOMMIT | OFF.</li>
 * </ul>
 */
public record ChaosRequest(@NotBlank(message = "Invalid chaos target or mode.") String target,
                           @NotBlank(message = "Invalid chaos target or mode.") String mode,
                           Long delayMs) {
}
