package com.claims.intake.api.dto;

import java.time.Instant;
import java.util.UUID;

/** GET /api/claims/{id}/status, polled by the frontend. */
public record StatusResponse(UUID id, String status, Instant updatedAt) {
}
