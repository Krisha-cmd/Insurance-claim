package com.claims.approval.service;

import com.claims.approval.domain.CoordinatorState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosSwitchTest {

    private final List<String> halts = new ArrayList<>();
    private final ChaosSwitch chaos = new ChaosSwitch(halts::add);

    @Test
    void crashAfterPrecommitFiresOnlyAtPrecommitting() {
        chaos.set(ChaosMode.CRASH_AFTER_PRECOMMIT);
        chaos.maybeCrash(CoordinatorState.WAITING_VOTES, () -> { });
        assertThat(halts).isEmpty();

        List<String> lastWords = new ArrayList<>();
        chaos.maybeCrash(CoordinatorState.PRECOMMITTING, () -> lastWords.add("bye"));
        assertThat(lastWords).containsExactly("bye");
        assertThat(halts).containsExactly("chaos CRASH_AFTER_PRECOMMIT at PRECOMMITTING");
    }

    @Test
    void crashBeforePrecommitFiresAtWaitingVotes() {
        chaos.set(ChaosMode.CRASH_BEFORE_PRECOMMIT);
        assertThat(chaos.armedFor(CoordinatorState.WAITING_VOTES)).isTrue();
        assertThat(chaos.armedFor(CoordinatorState.PRECOMMITTING)).isFalse();
        chaos.maybeCrash(CoordinatorState.WAITING_VOTES, () -> { });
        assertThat(halts).hasSize(1);
    }

    @Test
    void isOneShot() {
        chaos.set(ChaosMode.CRASH_AFTER_PRECOMMIT);
        chaos.maybeCrash(CoordinatorState.PRECOMMITTING, () -> { });
        chaos.maybeCrash(CoordinatorState.PRECOMMITTING, () -> { });
        assertThat(halts).hasSize(1);
        assertThat(chaos.mode()).isEqualTo(ChaosMode.NONE);
    }

    @Test
    void noneNeverCrashes() {
        for (CoordinatorState s : CoordinatorState.values()) chaos.maybeCrash(s, () -> { });
        assertThat(halts).isEmpty();
    }

    @Test
    void phasesMatchTheFrontendStepper() {
        assertThat(CoordinatorState.WAITING_VOTES.phase()).isEqualTo("CAN_COMMIT");
        assertThat(CoordinatorState.PRECOMMITTING.phase()).isEqualTo("PRE_COMMIT");
        assertThat(CoordinatorState.COMMITTING.phase()).isEqualTo("DO_COMMIT");
        assertThat(CoordinatorState.COMMITTED.phase()).isEqualTo("DONE");
        assertThat(CoordinatorState.ABORTED.phase()).isEqualTo("CAN_COMMIT");
        assertThat(SettlementViewMapper.displayName("claim-intake")).isEqualTo("CLAIM_INTAKE");
    }
}
