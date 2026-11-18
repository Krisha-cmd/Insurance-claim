package com.claims.approval.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The coordinator's state for one settlement, as written to its log.
 *
 * <pre>
 * WAITING_VOTES ──all YES──▶ PRECOMMITTING ──▶ COMMITTING ──all acked──▶ COMMITTED
 *       │
 *       └─NO / timeout / crash──▶ ABORTING ──▶ ABORTED
 * </pre>
 * Once PRECOMMITTING is logged, every participant has voted YES, so the only way out is commit.
 */
public enum CoordinatorState {
    WAITING_VOTES,
    PRECOMMITTING,
    COMMITTING,
    COMMITTED,
    ABORTING,
    ABORTED;

    public static final Set<CoordinatorState> UNFINISHED = EnumSet.of(WAITING_VOTES, PRECOMMITTING, COMMITTING, ABORTING);

    public boolean isFinal() {
        return this == COMMITTED || this == ABORTED;
    }

    /** The phase name the frontend's settlement stepper uses. */
    public String phase() {
        return switch (this) {
            case WAITING_VOTES, ABORTING, ABORTED -> "CAN_COMMIT"; // 3PC can only abort before PreCommit
            case PRECOMMITTING -> "PRE_COMMIT";
            case COMMITTING -> "DO_COMMIT";
            case COMMITTED -> "DONE";
        };
    }
}
