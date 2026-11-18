package com.claims.participant;

import java.time.Duration;

/**
 * The participant's own 3PC timeouts.
 *
 * @param prepareTimeout   PREPARED this long without PreCommit → abort locally. Must be much longer than the
 *                         coordinator's phase timeout plus its restart and recovery time, because a coordinator
 *                         that logged PRECOMMITTING will still send PreCommit/DoCommit after a crash.
 * @param precommitTimeout PRECOMMITTED this long without DoCommit → commit locally (everyone voted YES).
 */
public record ParticipantTimeouts(Duration prepareTimeout, Duration precommitTimeout) {

    public static ParticipantTimeouts defaults() {
        return new ParticipantTimeouts(Duration.ofMinutes(5), Duration.ofMinutes(2));
    }
}
