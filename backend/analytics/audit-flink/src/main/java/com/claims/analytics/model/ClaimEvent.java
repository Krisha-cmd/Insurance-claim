package com.claims.analytics.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One event from the {@code claim-events} topic. Every service publishes the same envelope;
 * fields that do not apply to an event type are {@code null}.
 *
 * <p>Flink POJO: public no-arg constructor plus getters/setters, so it is serialized by Flink's
 * PojoSerializer rather than Kryo.
 */
public class ClaimEvent {

    private String eventId;
    private String eventType;
    /** Event time, epoch millis. */
    private long occurredAt;
    private String service;
    private String claimId;
    private String policyNo;
    private String actorId;
    private String actorRole;
    private String fromStatus;
    private String toStatus;
    private BigDecimal amount;
    private String comment;
    /** The {@code change} object of the event, kept as raw JSON. */
    private String changeJson;

    // Three-phase-commit (TX_*) fields
    private String txId;
    private String phase;
    private String participant;
    private String vote;
    private String participantState;

    public ClaimEvent() {
    }

    public boolean isTxEvent() {
        return eventType != null && eventType.startsWith(EventTypes.TX_PREFIX) && txId != null;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public long getOccurredAt() { return occurredAt; }
    public void setOccurredAt(long occurredAt) { this.occurredAt = occurredAt; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }
    public String getPolicyNo() { return policyNo; }
    public void setPolicyNo(String policyNo) { this.policyNo = policyNo; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public String getChangeJson() { return changeJson; }
    public void setChangeJson(String changeJson) { this.changeJson = changeJson; }
    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getParticipant() { return participant; }
    public void setParticipant(String participant) { this.participant = participant; }
    public String getVote() { return vote; }
    public void setVote(String vote) { this.vote = vote; }
    public String getParticipantState() { return participantState; }
    public void setParticipantState(String participantState) { this.participantState = participantState; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClaimEvent that)) return false;
        return occurredAt == that.occurredAt && Objects.equals(eventId, that.eventId)
                && Objects.equals(eventType, that.eventType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, eventType, occurredAt);
    }

    @Override
    public String toString() {
        return "ClaimEvent{" + eventType + " " + eventId + " claim=" + claimId + " tx=" + txId
                + " at=" + occurredAt + "}";
    }
}
