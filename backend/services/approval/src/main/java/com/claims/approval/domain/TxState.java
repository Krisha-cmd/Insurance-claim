package com.claims.approval.domain;

import com.claims.proto.settlement.v1.ParticipantState;

/** A participant's state as last acknowledged to the coordinator. */
public enum TxState {
    UNKNOWN,
    PREPARED,
    PRECOMMITTED,
    COMMITTED,
    ABORTED;

    public static TxState from(ParticipantState s) {
        return switch (s) {
            case PREPARED -> PREPARED;
            case PRECOMMITTED -> PRECOMMITTED;
            case COMMITTED -> COMMITTED;
            case ABORTED -> ABORTED;
            default -> UNKNOWN;
        };
    }
}
