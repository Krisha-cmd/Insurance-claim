package com.claims.approval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param assessmentTarget gRPC target of the Assessment service (AssessmentService)
 * @param claimTarget      gRPC target of Claim Intake (ClaimService)
 * @param callTimeout      deadline for those two calls
 * @param currency         currency sent to participants
 * @param chaosEnabled     whether POST /internal/chaos exists
 */
@ConfigurationProperties("claims.approval")
public record ApprovalProperties(String assessmentTarget, String claimTarget, Duration callTimeout,
                                 String currency, boolean chaosEnabled) {
}
