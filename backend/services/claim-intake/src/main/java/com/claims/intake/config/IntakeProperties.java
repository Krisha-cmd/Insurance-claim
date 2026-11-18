package com.claims.intake.config;

import com.claims.intake.domain.DocType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@Validated
@ConfigurationProperties("claims.intake")
public record IntakeProperties(@NotNull String currency, @NotNull Documents documents, @NotNull Results results,
                               @NotNull Workflow workflow, @NotNull Participant participant) {

    /**
     * @param bucket              DOCUMENTS_BUCKET
     * @param requiredTypes       a claim reaches DOCS_VERIFIED once each has a VERIFIED document
     * @param allowedContentTypes must match the verification Lambda's ALLOWED_TYPES
     */
    public record Documents(String bucket, @NotNull Duration uploadUrlTtl, @NotNull Duration previewUrlTtl,
                            @NotEmpty List<String> allowedContentTypes, int maxSizeMb,
                            @NotEmpty Set<DocType> requiredTypes) {

        public long maxSizeBytes() {
            return maxSizeMb * 1024L * 1024L;
        }
    }

    /** SQS queue the verification Lambda writes results to (RESULTS_QUEUE_URL). Empty disables the listener. */
    public record Results(String queueUrl, int waitSeconds, int maxMessages) {
    }

    /**
     * @param checkTimeout deadline of each eligibility / assessment call
     * @param stuckAfter   a claim idle in DOCS_VERIFIED this long gets its checks started again
     */
    public record Workflow(@NotNull Duration checkTimeout, @NotNull Duration stuckAfter) {
    }

    /** This service's own 3PC timeouts (see ParticipantTimeouts). */
    public record Participant(@NotNull Duration prepareTimeout, @NotNull Duration precommitTimeout) {
    }
}
