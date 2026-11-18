package com.claims.analytics.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Fixed-width ISO-8601 UTC formats. A fixed width matters for DynamoDB sort keys: {@link Instant#toString()}
 * drops zero milliseconds, which would sort {@code 12Z} after {@code 12.331Z}.
 */
public final class Timestamps {

    private static final DateTimeFormatter MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {
    }

    /** e.g. {@code 2026-11-16T14:05:12.331Z}; used for audit events. */
    public static String isoMillis(long epochMillis) {
        return MILLIS.format(Instant.ofEpochMilli(epochMillis));
    }

    /** e.g. {@code 2026-11-16T14:05:00Z}; used for window boundaries. */
    public static String isoSeconds(long epochMillis) {
        return SECONDS.format(Instant.ofEpochMilli(epochMillis));
    }
}
