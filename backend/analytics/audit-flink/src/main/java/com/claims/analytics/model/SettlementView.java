package com.claims.analytics.model;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Current state of one three-phase-commit settlement transaction (powers the 3PC stepper). */
public class SettlementView {

    private String txId;
    private String claimId;
    private String coordinator;
    private String phase;
    private String outcome;
    private Map<String, Participant> participants = new TreeMap<>();
    private long startedAt;
    private long updatedAt;

    public SettlementView() {
    }

    public SettlementView(String txId) {
        this.txId = txId;
    }

    /** A copy, so values emitted downstream are never mutated by later state updates. */
    public SettlementView copy() {
        SettlementView c = new SettlementView(txId);
        c.claimId = claimId;
        c.coordinator = coordinator;
        c.phase = phase;
        c.outcome = outcome;
        participants.forEach((k, v) -> c.participants.put(k, new Participant(v.getVote(), v.getState())));
        c.startedAt = startedAt;
        c.updatedAt = updatedAt;
        return c;
    }

    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "SettlementView");
        doc.put("txId", txId);
        doc.put("claimId", claimId);
        doc.put("coordinator", coordinator);
        doc.put("phase", phase);
        doc.put("outcome", outcome);
        Map<String, Object> parts = new TreeMap<>();
        participants.forEach((name, p) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vote", p.getVote());
            m.put("state", p.getState());
            parts.put(name, m);
        });
        doc.put("participants", parts);
        doc.put("startedAt", startedAt == 0 ? null : Timestamps.isoMillis(startedAt));
        doc.put("updatedAt", updatedAt == 0 ? null : Timestamps.isoMillis(updatedAt));
        return doc;
    }

    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }
    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }
    public String getCoordinator() { return coordinator; }
    public void setCoordinator(String coordinator) { this.coordinator = coordinator; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Map<String, Participant> getParticipants() { return participants; }
    public void setParticipants(Map<String, Participant> participants) { this.participants = participants; }
    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "SettlementView" + toDocument();
    }

    /** One participant's vote ({@code YES | NO}) and state ({@code PREPARED | PRECOMMITTED | COMMITTED | ABORTED}). */
    public static class Participant implements Serializable {
        private String vote;
        private String state;

        public Participant() {
        }

        public Participant(String vote, String state) {
            this.vote = vote;
            this.state = state;
        }

        public String getVote() { return vote; }
        public void setVote(String vote) { this.vote = vote; }
        public String getState() { return state; }
        public void setState(String state) { this.state = state; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Participant p && Objects.equals(vote, p.vote) && Objects.equals(state, p.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(vote, state);
        }

        @Override
        public String toString() {
            return "{vote=" + vote + ", state=" + state + "}";
        }
    }
}
