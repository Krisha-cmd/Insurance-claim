package com.claims.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One message on the Kafka {@code claim-events} topic (key = claimId). Audit consumes these to build
 * the claim timeline and the settlement view; see "Audit Event" in the root README.
 *
 * @param eventType  e.g. CLAIM_APPROVED, SETTLEMENT_STARTED, TX_VOTE, TX_RECOVERY
 * @param service    the emitting service, e.g. "approval"
 * @param fromStatus claim status before the change, when the event changes it
 * @param toStatus   claim status after the change
 * @param change     free-form details (amounts, votes, reasons)
 * @param txId       3PC transaction id for settlement events, else null
 * @param phase      3PC phase (CAN_COMMIT, PRE_COMMIT, DO_COMMIT, DONE), else null
 */
public record ClaimEvent(
        UUID eventId,
        String eventType,
        UUID claimId,
        String service,
        Actor actor,
        String fromStatus,
        String toStatus,
        Map<String, Object> change,
        String comment,
        UUID txId,
        String phase,
        Instant timestamp) {

    public record Actor(String id, String role, String name) {

        public static Actor system(String service) {
            return new Actor("svc-" + service, "SYSTEM", service);
        }
    }
}
