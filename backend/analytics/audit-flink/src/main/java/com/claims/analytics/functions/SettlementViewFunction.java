package com.claims.analytics.functions;

import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.EventTypes;
import com.claims.analytics.model.SettlementView;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * Keyed by {@code txId}: folds every {@code TX_*} event into the transaction's {@link SettlementView} and emits
 * the updated view, which is upserted to DynamoDB as {@code TX#<txId> / STATE}.
 *
 * <p>Rules: {@code claimId} is filled in when known; the service of {@code TX_STARTED} is the coordinator;
 * a {@code phase} on any event moves the phase; an event with a {@code participant} sets that participant's
 * {@code vote} and/or {@code state}; {@code TX_COMMITTED} / {@code TX_ABORTED} set the outcome. Events may
 * arrive out of order, so {@code startedAt} is the earliest and {@code updatedAt} the latest event time seen.
 *
 * <p>State expires one day after the last write, so finished transactions do not grow state forever.
 */
public class SettlementViewFunction extends KeyedProcessFunction<String, ClaimEvent, SettlementView> {

    public static final Duration STATE_TTL = Duration.ofDays(1);

    private transient ValueState<SettlementView> state;

    @Override
    public void open(OpenContext openContext) {
        ValueStateDescriptor<SettlementView> descriptor =
                new ValueStateDescriptor<>("settlement-view", SettlementView.class);
        descriptor.enableTimeToLive(StateTtlConfig.newBuilder(STATE_TTL)
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .build());
        state = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(ClaimEvent e, Context ctx, Collector<SettlementView> out) throws Exception {
        SettlementView view = state.value();
        if (view == null) {
            view = new SettlementView(ctx.getCurrentKey());
        }
        apply(view, e);
        state.update(view);
        out.collect(view.copy());
    }

    static void apply(SettlementView view, ClaimEvent e) {
        if (e.getClaimId() != null) {
            view.setClaimId(e.getClaimId());
        }
        if (EventTypes.TX_STARTED.equals(e.getEventType()) && e.getService() != null) {
            view.setCoordinator(e.getService());
        }
        if (e.getPhase() != null) {
            view.setPhase(e.getPhase());
        }
        if (e.getParticipant() != null) {
            SettlementView.Participant p =
                    view.getParticipants().computeIfAbsent(e.getParticipant(), k -> new SettlementView.Participant());
            if (e.getVote() != null) {
                p.setVote(e.getVote());
            }
            if (e.getParticipantState() != null) {
                p.setState(e.getParticipantState());
            }
        }
        if (EventTypes.TX_COMMITTED.equals(e.getEventType())) {
            view.setOutcome("COMMITTED");
        } else if (EventTypes.TX_ABORTED.equals(e.getEventType())) {
            view.setOutcome("ABORTED");
        }
        long ts = e.getOccurredAt();
        if (view.getStartedAt() == 0 || ts < view.getStartedAt()) {
            view.setStartedAt(ts);
        }
        if (ts > view.getUpdatedAt()) {
            view.setUpdatedAt(ts);
        }
    }
}
