package com.claims.participant;

import com.claims.proto.settlement.v1.ParticipantState;

/** A participant's own state for one settlement, as in the ER diagrams (PREPARED | PRECOMMITTED | COMMITTED | ABORTED). */
public enum ParticipantTxState {
    PREPARED,
    PRECOMMITTED,
    COMMITTED,
    ABORTED;

    public boolean isFinal() {
        return this == COMMITTED || this == ABORTED;
    }

    public ParticipantState toProto() {
        return switch (this) {
            case PREPARED -> ParticipantState.PREPARED;
            case PRECOMMITTED -> ParticipantState.PRECOMMITTED;
            case COMMITTED -> ParticipantState.COMMITTED;
            case ABORTED -> ParticipantState.ABORTED;
        };
    }
}
