package com.claims.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One message on the Kafka {@code claim-events} topic (key = claimId). This is the envelope the Audit
 * Flink job reads (see backend/analytics/audit-flink/README.md, "Input events"): it requires
 * {@code eventId}, {@code eventType} and {@code occurredAt}; fields that do not apply are null.
 *
 * <ul>
 *   <li>{@code CLAIM_CREATED} with {@code policyNo} and {@code amount} feeds totals and fraud detection.</li>
 *   <li>Any event with {@code toStatus} is counted per status. Only Claim Intake (which owns the claim
 *       status) sets it, on {@code CLAIM_STATUS_CHANGED}, so a status change is never counted twice.</li>
 *   <li>{@code TX_*} events with {@code txId} build the settlement view; {@code TX_STARTED}'s service is the
 *       coordinator, and {@code participant}/{@code vote}/{@code participantState} set a participant's row.</li>
 * </ul>
 */
public record ClaimEvent(
        String eventId,
        String eventType,
        Instant occurredAt,
        String service,
        UUID claimId,
        String policyNo,
        Actor actor,
        String fromStatus,
        String toStatus,
        BigDecimal amount,
        Map<String, Object> change,
        String comment,
        UUID txId,
        String phase,
        String participant,
        String vote,
        String participantState) {

    public record Actor(String id, String role, String name) {

        public static Actor system(String service) {
            return new Actor("svc-" + service, "SYSTEM", service);
        }
    }

    public static Builder builder(String eventType, String service, UUID claimId, Instant occurredAt) {
        return new Builder(eventType, service, claimId, occurredAt);
    }

    public static final class Builder {
        private final String eventType;
        private final String service;
        private final UUID claimId;
        private final Instant occurredAt;
        private String policyNo;
        private Actor actor;
        private String fromStatus;
        private String toStatus;
        private BigDecimal amount;
        private Map<String, Object> change;
        private String comment;
        private UUID txId;
        private String phase;
        private String participant;
        private String vote;
        private String participantState;

        private Builder(String eventType, String service, UUID claimId, Instant occurredAt) {
            this.eventType = eventType;
            this.service = service;
            this.claimId = claimId;
            this.occurredAt = occurredAt;
            this.actor = Actor.system(service);
        }

        public Builder policyNo(String v) { this.policyNo = v; return this; }
        public Builder actor(Actor v) { this.actor = v; return this; }
        public Builder status(String from, String to) { this.fromStatus = from; this.toStatus = to; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder change(Map<String, Object> v) { this.change = v; return this; }
        public Builder comment(String v) { this.comment = v; return this; }
        public Builder tx(UUID txId, String phase) { this.txId = txId; this.phase = phase; return this; }
        public Builder participant(String name, String vote, String state) {
            this.participant = name;
            this.vote = vote;
            this.participantState = state;
            return this;
        }

        /** Adds one entry to {@code change}. */
        public Builder detail(String key, Object value) {
            if (change == null) change = new LinkedHashMap<>();
            change.put(key, value);
            return this;
        }

        public ClaimEvent build() {
            return new ClaimEvent(UUID.randomUUID().toString(), eventType, occurredAt, service, claimId, policyNo,
                    actor, fromStatus, toStatus, amount, change, comment, txId, phase, participant, vote,
                    participantState);
        }
    }
}
