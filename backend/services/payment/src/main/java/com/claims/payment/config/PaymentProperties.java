package com.claims.payment.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("claims.payment")
public record PaymentProperties(@NotNull String currency, @NotNull Duration claimLookupTimeout,
                                @NotNull Gateway gateway, @NotNull Participant participant) {

    /**
     * @param readTimeout     a call slower than this fails (TIMEOUT); prepare then votes NO
     * @param captureAttempts capture tries inside DoCommit before leaving it to the recovery sweep
     * @param captureBackoff  wait after the first failed capture; doubles each time
     */
    public record Gateway(@NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout,
                          @Min(1) int captureAttempts, @NotNull Duration captureBackoff) {
    }

    public record Participant(@NotNull Duration prepareTimeout, @NotNull Duration precommitTimeout) {
    }
}
