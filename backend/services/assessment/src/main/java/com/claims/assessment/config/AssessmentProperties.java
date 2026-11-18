package com.claims.assessment.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param currency    the currency every amount is in (INR)
 * @param callTimeout deadline of each gRPC call to Policy and Claim Intake
 */
@Validated
@ConfigurationProperties("claims.assessment")
public record AssessmentProperties(@NotBlank String currency, @NotNull Duration callTimeout) {
}
