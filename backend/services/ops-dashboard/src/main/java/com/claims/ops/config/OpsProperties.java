package com.claims.ops.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param table           the claims-analytics table (ANALYTICS_TABLE)
 * @param lookbackMinutes how far back the summary and the default series reach (the frontend says "last 30 min")
 * @param maxAuditResults cap on events read for one audit search
 * @param approvalUrl     Approval's base URL (for /internal/chaos)
 * @param paymentUrl      Payment's base URL (for /internal/chaos)
 */
@Validated
@ConfigurationProperties("claims.ops")
public record OpsProperties(@NotBlank String table, @NotBlank String gsi1, @Min(1) int lookbackMinutes,
                            @Min(1) int maxAuditResults, @NotBlank String approvalUrl, @NotBlank String paymentUrl,
                            @NotNull Duration chaosTimeout) {
}
