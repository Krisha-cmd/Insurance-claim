package com.claims.intake.messaging;

import com.claims.intake.domain.VerificationStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One message on the doc-verification-results queue, written by the document-verification Lambda:
 * <pre>
 * {"documentId":"…","claimId":"…","s3Key":"claims/{claimId}/{docType}/{documentId}-{file}",
 *  "docType":"FINAL_BILL","status":"VERIFIED"|"REJECTED","reason":"…","checksum":"sha256:…",
 *  "checkedAt":"2026-11-18T10:00:00Z"}
 * </pre>
 * Only {@code status} and one of {@code documentId} / {@code s3Key} are required; the id is parsed from the
 * key when it is missing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationResultMessage(String documentId, String claimId, String s3Key, String docType,
                                        String status, String reason, String checksum, Instant checkedAt) {

    private static final Pattern KEY = Pattern.compile(
            "^claims/[0-9a-fA-F-]{36}/[A-Z_]+/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})-.*$");

    public UUID resolveDocumentId() {
        if (documentId != null && !documentId.isBlank()) {
            return UUID.fromString(documentId.trim());
        }
        if (s3Key != null) {
            Matcher m = KEY.matcher(s3Key);
            if (m.matches()) return UUID.fromString(m.group(1));
        }
        throw new IllegalArgumentException("Message has neither a documentId nor a claims/… s3Key");
    }

    public VerificationStatus verificationStatus() {
        if (status == null) throw new IllegalArgumentException("Message has no status");
        VerificationStatus s = VerificationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        if (s == VerificationStatus.PENDING) throw new IllegalArgumentException("A result cannot be PENDING");
        return s;
    }

    public Instant checkedAtOr(Instant fallback) {
        return checkedAt != null ? checkedAt : fallback;
    }
}
