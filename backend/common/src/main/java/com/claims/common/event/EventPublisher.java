package com.claims.common.event;

import java.time.Duration;

/**
 * Publishes to {@code claim-events}. Never fails the caller: each service's own database is the source of
 * truth, events are for the audit trail and analytics. Build one with {@link EventPublishers#create}.
 */
public interface EventPublisher {

    void publish(ClaimEvent event);

    /** Waits (up to {@code timeout}) until the event is handed to the broker, e.g. right before a chaos crash. */
    default void publishAndWait(ClaimEvent event, Duration timeout) {
        publish(event);
    }
}
