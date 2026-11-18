package com.claims.docverify;

import java.time.Instant;
import java.util.UUID;

/**
 * The message sent to RESULTS_QUEUE_URL; Claim Intake's VerificationResultListener reads it.
 * {@code s3Key} and {@code checksum} (the S3 ETag) are extras Claim Intake stores; {@code reason} is null
 * when VERIFIED.
 */
public record VerificationResult(UUID claimId, UUID documentId, String docType, VerificationStatus status,
                                 String reason, Instant checkedAt, String s3Key, String checksum) {
}
