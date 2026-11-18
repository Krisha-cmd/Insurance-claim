package com.claims.policy.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param claimLookupTimeout deadline of the ClaimService.GetClaim call made in prepare
 */
@Validated
@ConfigurationProperties("claims.policy")
public record PolicyProperties(@NotNull String currency, @NotNull Duration claimLookupTimeout,
                               @NotNull Participant participant) {

    /** This service's own 3PC timeouts (see ParticipantTimeouts). */
    public record Participant(@NotNull Duration prepareTimeout, @NotNull Duration precommitTimeout) {
    }
}
