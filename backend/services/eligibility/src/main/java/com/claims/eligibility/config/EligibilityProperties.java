package com.claims.eligibility.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** @param callTimeout deadline of each gRPC call to Policy and Claim Intake */
@Validated
@ConfigurationProperties("claims.eligibility")
public record EligibilityProperties(@NotNull Duration callTimeout) {
}
