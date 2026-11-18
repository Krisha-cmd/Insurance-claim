package com.claims.analytics;

import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.EventTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Test event builders. Times are given as offsets from a fixed, minute-aligned base instant. */
public final class Events {

    public static final long BASE = Instant.parse("2026-11-16T14:00:00Z").toEpochMilli();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private Events() {
    }

    public static long at(int minutes, int seconds) {
        return BASE + minutes * 60_000L + seconds * 1_000L;
    }

    public static ClaimEvent event(String type, long ts) {
        ClaimEvent e = new ClaimEvent();
        e.setEventId("e-" + SEQ.incrementAndGet());
        e.setEventType(type);
        e.setOccurredAt(ts);
        return e;
    }

    public static ClaimEvent created(String claimId, String policyNo, long ts, String amount) {
        ClaimEvent e = event(EventTypes.CLAIM_CREATED, ts);
        e.setService("claim-intake");
        e.setClaimId(claimId);
        e.setPolicyNo(policyNo);
        e.setToStatus("SUBMITTED");
        e.setAmount(new BigDecimal(amount));
        return e;
    }

    public static ClaimEvent status(String claimId, String toStatus, long ts) {
        ClaimEvent e = event("CLAIM_STATUS_CHANGED", ts);
        e.setClaimId(claimId);
        e.setToStatus(toStatus);
        return e;
    }

    public static ClaimEvent tx(String type, String txId, long ts) {
        ClaimEvent e = event(type, ts);
        e.setService("approval");
        e.setTxId(txId);
        e.setClaimId("c-tx");
        return e;
    }

    public static ClaimEvent participant(String txId, String participant, String vote, String state, long ts) {
        ClaimEvent e = tx(vote != null ? EventTypes.TX_VOTE : EventTypes.TX_PARTICIPANT_STATE, txId, ts);
        e.setService(participant);
        e.setParticipant(participant);
        e.setVote(vote);
        e.setParticipantState(state);
        return e;
    }
}
