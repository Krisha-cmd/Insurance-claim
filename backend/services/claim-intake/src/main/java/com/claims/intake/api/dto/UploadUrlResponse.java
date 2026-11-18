package com.claims.intake.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Where the browser PUTs the file. It must send exactly {@code headers} (the signed Content-Type).
 *
 * @param uploadUrl pre-signed S3 PUT URL, valid until {@code expiresAt}
 */
public record UploadUrlResponse(String uploadUrl, UUID documentId, String key, String method,
                                Map<String, String> headers, Instant expiresAt) {
}
