package com.claims.analytics.functions;

import com.claims.analytics.model.ClaimEvent;
import org.apache.flink.api.common.functions.AggregateFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Collects the distinct claim IDs created for one policy inside one window (a replayed event counts once). */
public class FraudCountAggregate
        implements AggregateFunction<ClaimEvent, FraudCountAggregate.ClaimIdsAcc, List<String>> {

    @Override
    public ClaimIdsAcc createAccumulator() {
        return new ClaimIdsAcc();
    }

    @Override
    public ClaimIdsAcc add(ClaimEvent e, ClaimIdsAcc acc) {
        if (e.getClaimId() != null) {
            acc.claimIds.add(e.getClaimId());
        }
        return acc;
    }

    @Override
    public List<String> getResult(ClaimIdsAcc acc) {
        return new ArrayList<>(acc.claimIds);
    }

    @Override
    public ClaimIdsAcc merge(ClaimIdsAcc a, ClaimIdsAcc b) {
        ClaimIdsAcc m = new ClaimIdsAcc();
        m.claimIds.addAll(a.claimIds);
        m.claimIds.addAll(b.claimIds);
        return m;
    }

    /** Window accumulator; a sorted set keeps the emitted claim list deterministic. */
    public static class ClaimIdsAcc {
        public TreeSet<String> claimIds = new TreeSet<>();
    }
}
