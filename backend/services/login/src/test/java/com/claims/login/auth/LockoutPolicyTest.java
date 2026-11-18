package com.claims.login.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LockoutPolicyTest {

    private final LockoutPolicy policy = new LockoutPolicy(5, Duration.ofMinutes(15));
    private final Instant now = Instant.parse("2026-11-16T10:00:00Z");

    @Test
    void locksOnTheFifthFailure() {
        assertThat(policy.shouldLock(4)).isFalse();
        assertThat(policy.shouldLock(5)).isTrue();
        assertThat(policy.shouldLock(6)).isTrue();
    }

    @Test
    void countsFailuresInTheLast15Minutes() {
        assertThat(policy.countFailuresAfter(now, null)).isEqualTo(now.minus(Duration.ofMinutes(15)));
    }

    @Test
    void aRecentSuccessResetsTheCount() {
        Instant success = now.minus(Duration.ofMinutes(3));
        assertThat(policy.countFailuresAfter(now, success)).isEqualTo(success);
    }

    @Test
    void anOldSuccessDoesNotWidenTheWindow() {
        Instant success = now.minus(Duration.ofHours(2));
        assertThat(policy.countFailuresAfter(now, success)).isEqualTo(now.minus(Duration.ofMinutes(15)));
    }

    @Test
    void lockLasts15MinutesAfterTheLastFailure() {
        assertThat(policy.lockExpired(now.minus(Duration.ofMinutes(14)), now)).isFalse();
        assertThat(policy.lockExpired(now.minus(Duration.ofMinutes(15)), now)).isTrue();
        assertThat(policy.lockExpired(null, now)).isTrue();
    }
}
