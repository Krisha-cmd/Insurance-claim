package com.claims.approval.events;

import com.claims.common.event.ClaimEvent;

import java.time.Duration;

/**
 * Publishes claim and settlement events to the {@code claim-events} topic. Publishing never fails the
 * caller: the coordinator's log in Postgres is the source of truth, events are for the audit trail.
 */
public interface AuditPublisher {

    void publish(ClaimEvent event);

    /** Waits (up to {@code timeout}) until the event is handed to the broker, e.g. right before a chaos crash. */
    default void publishAndWait(ClaimEvent event, Duration timeout) {
        publish(event);
    }
}
