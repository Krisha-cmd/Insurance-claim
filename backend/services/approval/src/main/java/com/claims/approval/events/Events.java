package com.claims.approval.events;

import com.claims.common.event.ClaimEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Builders for the events this service emits. */
public final class Events {

    public static final String SERVICE = "approval";
    public static final ClaimEvent.Actor COORDINATOR = ClaimEvent.Actor.system(SERVICE);

    private Events() {
    }

    /** A claim status change made by a person (decision, retry). */
    public static ClaimEvent claim(String type, UUID claimId, ClaimEvent.Actor actor, String fromStatus,
                                   String toStatus, Map<String, Object> change, String comment, UUID txId, Instant at) {
        return new ClaimEvent(UUID.randomUUID(), type, claimId, SERVICE, actor, fromStatus, toStatus,
                change, comment, txId, null, at);
    }

    /** The settlement committed: the claim is paid. */
    public static ClaimEvent claimSettled(UUID claimId, UUID txId, Map<String, Object> change, Instant at) {
        return new ClaimEvent(UUID.randomUUID(), "CLAIM_SETTLED", claimId, SERVICE, COORDINATOR, null, "SETTLED",
                change, null, txId, "DONE", at);
    }

    /** A step of a 3PC settlement, emitted by the coordinator. */
    public static ClaimEvent tx(String type, UUID claimId, UUID txId, String phase, Map<String, Object> change, Instant at) {
        return new ClaimEvent(UUID.randomUUID(), type, claimId, SERVICE, COORDINATOR, null, null,
                change, null, txId, phase, at);
    }
}
