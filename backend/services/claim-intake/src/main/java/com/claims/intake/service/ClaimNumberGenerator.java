package com.claims.intake.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;

/**
 * CLM-&lt;yyyy&gt;-&lt;6 digits&gt;, e.g. CLM-2026-000042. The number comes from the {@code claim_no_seq} sequence, so
 * it is unique across replicas without locking; it does not restart each year (keeping it unique even when a
 * claim is filed around midnight on 31 December).
 */
@Component
public class ClaimNumberGenerator {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ClaimNumberGenerator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String next() {
        Long n = jdbc.queryForObject("SELECT nextval('claim_no_seq')", Long.class);
        int year = clock.instant().atZone(ZoneOffset.UTC).getYear();
        return format(year, n);
    }

    static String format(int year, long n) {
        return String.format("CLM-%d-%06d", year, n % 1_000_000);
    }
}
