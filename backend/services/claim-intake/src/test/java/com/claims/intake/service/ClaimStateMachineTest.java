package com.claims.intake.service;

import com.claims.intake.domain.ClaimStateMachine;
import com.claims.intake.domain.ClaimStatus;
import org.junit.jupiter.api.Test;

import static com.claims.intake.domain.ClaimStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The transition table on its own (no Spring). */
class ClaimStateMachineTest {

    private final ClaimStateMachine sm = new ClaimStateMachine(null, null, null, null);

    @Test
    void happyPath() {
        ClaimStatus[] path = {SUBMITTED, DOCS_PENDING, DOCS_VERIFIED, ELIGIBILITY_CHECKED, ASSESSED, UNDER_REVIEW,
                APPROVED, SETTLING, SETTLED};
        for (int i = 0; i + 1 < path.length; i++) {
            assertThat(sm.isAllowed(path[i], path[i + 1])).as(path[i] + " → " + path[i + 1]).isTrue();
        }
    }

    @Test
    void aFailedCheckSkipsItsStepButNotTheReview() {
        assertThat(sm.isAllowed(DOCS_VERIFIED, ASSESSED)).isTrue();
        assertThat(sm.isAllowed(DOCS_VERIFIED, UNDER_REVIEW)).isTrue();
        assertThat(sm.isAllowed(ELIGIBILITY_CHECKED, UNDER_REVIEW)).isTrue();
    }

    @Test
    void settlementCanFailAndBeRetried() {
        assertThat(sm.isAllowed(SETTLING, SETTLEMENT_FAILED)).isTrue();
        assertThat(sm.isAllowed(APPROVED, SETTLEMENT_FAILED)).isTrue();
        assertThat(sm.isAllowed(SETTLEMENT_FAILED, SETTLING)).isTrue();
    }

    @Test
    void illegalMoves() {
        assertThat(sm.isAllowed(SUBMITTED, APPROVED)).isFalse();
        assertThat(sm.isAllowed(DOCS_PENDING, UNDER_REVIEW)).isFalse();
        assertThat(sm.isAllowed(UNDER_REVIEW, SETTLED)).isFalse();
        assertThat(sm.isAllowed(APPROVED, SETTLED)).as("only through SETTLING").isFalse();
        assertThat(sm.isAllowed(REJECTED, APPROVED)).isFalse();
        assertThat(sm.isAllowed(SETTLED, SETTLEMENT_FAILED)).isFalse();
        for (ClaimStatus s : ClaimStatus.values()) {
            assertThat(sm.isAllowed(SETTLED, s)).isFalse();
            assertThat(sm.isAllowed(REJECTED, s)).isFalse();
        }
    }
}
