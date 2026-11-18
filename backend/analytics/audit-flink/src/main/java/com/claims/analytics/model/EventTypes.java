package com.claims.analytics.model;

/** Event types on {@code claim-events} that the job treats specially. Every other type is only audited. */
public final class EventTypes {

    public static final String CLAIM_CREATED = "CLAIM_CREATED";

    /** Every three-phase-commit event type starts with this prefix and carries a {@code txId}. */
    public static final String TX_PREFIX = "TX_";
    public static final String TX_STARTED = "TX_STARTED";
    public static final String TX_PHASE_CHANGED = "TX_PHASE_CHANGED";
    public static final String TX_VOTE = "TX_VOTE";
    public static final String TX_PARTICIPANT_STATE = "TX_PARTICIPANT_STATE";
    public static final String TX_COMMITTED = "TX_COMMITTED";
    public static final String TX_ABORTED = "TX_ABORTED";

    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    private EventTypes() {
    }
}
