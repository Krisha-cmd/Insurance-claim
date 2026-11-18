package com.claims.approval.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * 3PC coordinator settings. Participants are a plain list, so adding one is configuration only:
 * <pre>
 * claims.coordinator.participants[3].name=loyalty
 * claims.coordinator.participants[3].target=dns:///loyalty:9090
 * </pre>
 *
 * @param phaseTimeout       deadline for each phase's calls (CanCommit, PreCommit, DoCommit, Abort)
 * @param recoveryStaleAfter an unfinished tx is recovered only after it has been idle this long
 */
@Validated
@ConfigurationProperties("claims.coordinator")
public record CoordinatorProperties(@NotNull Duration phaseTimeout,
                                    @NotNull Duration recoveryStaleAfter,
                                    @NotEmpty @Valid List<Participant> participants) {

    public record Participant(@NotBlank String name, @NotBlank String target) {
    }
}
