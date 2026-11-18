package com.claims.intake.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A document as the frontend shows it.
 *
 * @param status     PENDING, VERIFIED or REJECTED (same as {@code verificationStatus})
 * @param reason     why it was rejected
 * @param previewUrl short-lived pre-signed GET URL, null until the document has been verified or rejected
 */
public record DocumentResponse(UUID id, String docType, String fileName, String contentType, Long size,
                               String status, String verificationStatus, String reason, Instant uploadedAt,
                               Instant verifiedAt, String previewUrl) {
}
