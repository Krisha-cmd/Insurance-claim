package com.claims.intake.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Real time plus an offset tests can move forward (participant timeouts). */
public class TickingClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration d) {
        offset = offset.plus(d);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.now().plus(offset); }
}
