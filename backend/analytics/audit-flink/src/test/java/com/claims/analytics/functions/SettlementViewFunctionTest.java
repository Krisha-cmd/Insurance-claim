package com.claims.analytics.functions;

import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.EventTypes;
import com.claims.analytics.model.SettlementView;
import com.claims.analytics.model.SettlementView.Participant;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.claims.analytics.Events.at;
import static com.claims.analytics.Events.participant;
import static com.claims.analytics.Events.tx;
import static org.assertj.core.api.Assertions.assertThat;

class SettlementViewFunctionTest {

    private KeyedOneInputStreamOperatorTestHarness<String, ClaimEvent, SettlementView> harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new KeyedOneInputStreamOperatorTestHarness<>(
                new KeyedProcessOperator<>(new SettlementViewFunction()), ClaimEvent::getTxId, Types.STRING);
        harness.open();
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    private void send(ClaimEvent e) throws Exception {
        harness.processElement(new StreamRecord<>(e, e.getOccurredAt()));
    }

    private SettlementView last() {
        List<SettlementView> out = harness.extractOutputValues();
        return out.get(out.size() - 1);
    }

    @Test
    void foldsAFullCommitIntoOneView() throws Exception {
        ClaimEvent start = tx(EventTypes.TX_STARTED, "tx-1", at(6, 1));
        start.setPhase("PREPARE");
        send(start);
        send(participant("tx-1", "policy", "YES", "PREPARED", at(6, 2)));
        send(participant("tx-1", "payment", "YES", "PREPARED", at(6, 2)));
        ClaimEvent precommit = tx(EventTypes.TX_PHASE_CHANGED, "tx-1", at(6, 3));
        precommit.setPhase("PRECOMMIT");
        send(precommit);
        send(participant("tx-1", "policy", null, "PRECOMMITTED", at(6, 4)));

        SettlementView v = last();
        assertThat(v.getTxId()).isEqualTo("tx-1");
        assertThat(v.getClaimId()).isEqualTo("c-tx");
        assertThat(v.getCoordinator()).isEqualTo("approval");
        assertThat(v.getPhase()).isEqualTo("PRECOMMIT");
        assertThat(v.getOutcome()).isNull();
        assertThat(v.getParticipants())
                .containsEntry("policy", new Participant("YES", "PRECOMMITTED"))
                .containsEntry("payment", new Participant("YES", "PREPARED"));

        ClaimEvent commit = tx(EventTypes.TX_COMMITTED, "tx-1", at(6, 5));
        commit.setPhase("COMMIT");
        send(commit);

        v = last();
        assertThat(v.getPhase()).isEqualTo("COMMIT");
        assertThat(v.getOutcome()).isEqualTo("COMMITTED");
        assertThat(v.getStartedAt()).isEqualTo(at(6, 1));
        assertThat(v.getUpdatedAt()).isEqualTo(at(6, 5));
        assertThat(harness.extractOutputValues()).hasSize(6);
    }

    @Test
    void keepsTransactionsApartAndRecordsAborts() throws Exception {
        send(participant("tx-a", "payment", "NO", "ABORTED", at(1, 0)));
        send(tx(EventTypes.TX_ABORTED, "tx-a", at(1, 1)));
        send(participant("tx-b", "policy", "YES", "PREPARED", at(1, 2)));

        List<SettlementView> out = harness.extractOutputValues();
        assertThat(out.get(1).getTxId()).isEqualTo("tx-a");
        assertThat(out.get(1).getOutcome()).isEqualTo("ABORTED");
        assertThat(out.get(2).getTxId()).isEqualTo("tx-b");
        assertThat(out.get(2).getOutcome()).isNull();
        assertThat(out.get(2).getParticipants()).containsOnlyKeys("policy");
    }

    @Test
    void outOfOrderEventsKeepEarliestStartAndLatestUpdate() throws Exception {
        send(participant("tx-1", "policy", "YES", "PREPARED", at(5, 0)));
        send(tx(EventTypes.TX_STARTED, "tx-1", at(4, 0)));

        SettlementView v = last();
        assertThat(v.getStartedAt()).isEqualTo(at(4, 0));
        assertThat(v.getUpdatedAt()).isEqualTo(at(5, 0));
    }

    @Test
    void emittedViewsAreSnapshots() throws Exception {
        send(participant("tx-1", "policy", "YES", "PREPARED", at(0, 0)));
        send(participant("tx-1", "policy", null, "COMMITTED", at(0, 1)));

        List<SettlementView> out = harness.extractOutputValues();
        assertThat(out.get(0).getParticipants().get("policy").getState()).isEqualTo("PREPARED");
        assertThat(out.get(1).getParticipants().get("policy").getState()).isEqualTo("COMMITTED");
    }
}
