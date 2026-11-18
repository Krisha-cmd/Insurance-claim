package com.claims.ops.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One minute of a series. claims_by_status fills submitted/approved/rejected/settled; aborts fills
 * aborts/commits. The other fields are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeriesPoint(Instant windowStart, Long submitted, Long approved, Long rejected, Long settled,
                          Long aborts, Long commits) {

    public static SeriesPoint byStatus(Instant at, long submitted, long approved, long rejected, long settled) {
        return new SeriesPoint(at, submitted, approved, rejected, settled, null, null);
    }

    public static SeriesPoint outcomes(Instant at, long aborts, long commits) {
        return new SeriesPoint(at, null, null, null, null, aborts, commits);
    }
}
