package com.claims.login.auth;

import com.claims.login.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The lockout rule, kept free of I/O so it can be unit-tested on its own.
 *
 * <ul>
 *   <li>Failures are counted inside the last {@code window} and after the last successful login.</li>
 *   <li>{@code maxFailures} such failures lock the account.</li>
 *   <li>A locked account unlocks once {@code window} has passed since its most recent failed attempt
 *       (attempts made while locked count, so hammering a locked account keeps it locked).</li>
 * </ul>
 */
@Component
public class LockoutPolicy {

    private final int maxFailures;
    private final Duration window;

    @Autowired
    public LockoutPolicy(AppProperties props) {
        this(props.lockout().maxFailures(), props.lockout().window());
    }

    public LockoutPolicy(int maxFailures, Duration window) {
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** Count failures strictly after this instant. */
    public Instant countFailuresAfter(Instant now, Instant lastSuccess) {
        Instant windowStart = now.minus(window);
        return lastSuccess != null && lastSuccess.isAfter(windowStart) ? lastSuccess : windowStart;
    }

    public boolean shouldLock(long recentFailures) {
        return recentFailures >= maxFailures;
    }

    public boolean lockExpired(Instant lastFailure, Instant now) {
        return lastFailure == null || !lastFailure.isAfter(now.minus(window));
    }

    public Duration window() {
        return window;
    }
}
