package com.claims.analytics.functions;

import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.ClaimsOverview;
import com.claims.analytics.model.EventTypes;
import org.apache.flink.api.common.functions.AggregateFunction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Incremental claims-overview metrics: per window only this small accumulator is kept, never the events.
 *
 * <ul>
 *   <li>{@code countsByStatus}: events per {@code toStatus}</li>
 *   <li>{@code approvalRate}: APPROVED / (APPROVED + REJECTED), 0 when there were no decisions</li>
 *   <li>{@code totalClaimed} / {@code avgClaimed}: over the {@code amount} of {@code CLAIM_CREATED} events</li>
 *   <li>{@code settlementCommits} / {@code settlementAborts}: {@code TX_COMMITTED} / {@code TX_ABORTED} events</li>
 * </ul>
 */
public class ClaimsOverviewAggregate
        implements AggregateFunction<ClaimEvent, ClaimsOverviewAggregate.OverviewAcc, ClaimsOverview> {

    @Override
    public OverviewAcc createAccumulator() {
        return new OverviewAcc();
    }

    @Override
    public OverviewAcc add(ClaimEvent e, OverviewAcc acc) {
        if (e.getToStatus() != null) {
            acc.countsByStatus.merge(e.getToStatus(), 1L, Long::sum);
        }
        switch (e.getEventType()) {
            case EventTypes.CLAIM_CREATED -> {
                acc.claimsCreated++;
                if (e.getAmount() != null) {
                    acc.totalClaimed = acc.totalClaimed.add(e.getAmount());
                }
            }
            case EventTypes.TX_COMMITTED -> acc.settlementCommits++;
            case EventTypes.TX_ABORTED -> acc.settlementAborts++;
            default -> {
            }
        }
        return acc;
    }

    @Override
    public ClaimsOverview getResult(OverviewAcc acc) {
        ClaimsOverview o = new ClaimsOverview();
        o.setCountsByStatus(new HashMap<>(acc.countsByStatus));
        long approved = acc.countsByStatus.getOrDefault(EventTypes.STATUS_APPROVED, 0L);
        long rejected = acc.countsByStatus.getOrDefault(EventTypes.STATUS_REJECTED, 0L);
        o.setApprovalRate(approved + rejected == 0 ? 0.0 : (double) approved / (approved + rejected));
        o.setClaimsCreated(acc.claimsCreated);
        o.setTotalClaimed(acc.totalClaimed);
        o.setAvgClaimed(acc.claimsCreated == 0
                ? BigDecimal.ZERO
                : acc.totalClaimed.divide(BigDecimal.valueOf(acc.claimsCreated), 2, RoundingMode.HALF_UP));
        o.setSettlementCommits(acc.settlementCommits);
        o.setSettlementAborts(acc.settlementAborts);
        return o;
    }

    @Override
    public OverviewAcc merge(OverviewAcc a, OverviewAcc b) {
        OverviewAcc m = new OverviewAcc();
        m.countsByStatus.putAll(a.countsByStatus);
        b.countsByStatus.forEach((k, v) -> m.countsByStatus.merge(k, v, Long::sum));
        m.claimsCreated = a.claimsCreated + b.claimsCreated;
        m.totalClaimed = a.totalClaimed.add(b.totalClaimed);
        m.settlementCommits = a.settlementCommits + b.settlementCommits;
        m.settlementAborts = a.settlementAborts + b.settlementAborts;
        return m;
    }

    /** Window accumulator (Flink POJO). */
    public static class OverviewAcc {
        public Map<String, Long> countsByStatus = new HashMap<>();
        public long claimsCreated;
        public BigDecimal totalClaimed = BigDecimal.ZERO;
        public long settlementCommits;
        public long settlementAborts;
    }
}
