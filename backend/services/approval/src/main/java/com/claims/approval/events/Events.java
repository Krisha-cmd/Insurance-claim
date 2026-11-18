package com.claims.approval.events;

import com.claims.common.event.ClaimEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Builders for the events this service emits. None of them set {@code toStatus}: the claim's status is
 * owned by Claim Intake, which emits CLAIM_STATUS_CHANGED for every change (Approval asks for the change
 * through ClaimService.UpdateStatus). That keeps the Flink job from counting a status twice.
 */
public final class Events {

    public static final String SERVICE = "approval";
    public static final ClaimEvent.Actor COORDINATOR = ClaimEvent.Actor.system(SERVICE);

    private Events() {
    }

    /** Something a person did (decision, retry). */
    public static ClaimEvent claim(String type, UUID claimId, ClaimEvent.Actor actor, BigDecimal amount,
                                   Map<String, Object> change, String comment, UUID txId, Instant at) {
        return ClaimEvent.builder(type, SERVICE, claimId, at).actor(actor).amount(amount).change(change)
                .comment(comment).tx(txId, null).build();
    }

    /** The settlement committed: the claim is paid. */
    public static ClaimEvent claimSettled(UUID claimId, UUID txId, BigDecimal amount, Map<String, Object> change, Instant at) {
        return ClaimEvent.builder("CLAIM_SETTLED", SERVICE, claimId, at).actor(COORDINATOR).amount(amount)
                .change(change).tx(txId, "DONE").build();
    }

    /** A step of a 3PC settlement, emitted by the coordinator. */
    public static ClaimEvent tx(String type, UUID claimId, UUID txId, String phase, Map<String, Object> change, Instant at) {
        return ClaimEvent.builder(type, SERVICE, claimId, at).actor(COORDINATOR).change(change).tx(txId, phase).build();
    }

    /** TX_VOTE as the coordinator received it. {@code vote} is YES, NO or NONE (no reply). */
    public static ClaimEvent vote(UUID claimId, UUID txId, String participant, String vote, Map<String, Object> change, Instant at) {
        String state = switch (vote) {
            case "YES" -> "PREPARED";
            case "NO" -> "ABORTED";
            default -> null;
        };
        return ClaimEvent.builder("TX_VOTE", SERVICE, claimId, at).actor(COORDINATOR).change(change)
                .tx(txId, "CAN_COMMIT").participant(participant, "NONE".equals(vote) ? null : vote, state).build();
    }
}
