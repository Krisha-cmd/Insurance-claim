package com.claims.common.error;

import java.time.Instant;

/**
 * The one error body every service returns. The frontend's API client reads {@code message}.
 */
public record ApiError(int status, String message, String path, Instant timestamp) {

    public static ApiError of(int status, String message, String path) {
        return new ApiError(status, message, path, Instant.now());
    }
}
