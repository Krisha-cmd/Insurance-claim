package com.claims.analytics.functions;

import com.claims.analytics.model.ClaimsOverview;
import com.claims.analytics.model.EventTypes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.claims.analytics.Events.created;
import static com.claims.analytics.Events.status;
import static com.claims.analytics.Events.tx;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimsOverviewAggregateTest {

    private final ClaimsOverviewAggregate agg = new ClaimsOverviewAggregate();

    @Test
    void countsStatusesAmountsAndSettlements() {
        var acc = agg.createAccumulator();
        agg.add(created("c-1", "P-1", 0, "1000"), acc);
        agg.add(created("c-2", "P-2", 0, "2000.50"), acc);
        agg.add(created("c-3", "P-3", 0, "3000"), acc);
        agg.add(status("c-1", "APPROVED", 0), acc);
        agg.add(status("c-2", "APPROVED", 0), acc);
        agg.add(status("c-3", "APPROVED", 0), acc);
        agg.add(status("c-4", "REJECTED", 0), acc);
        agg.add(tx(EventTypes.TX_COMMITTED, "tx-1", 0), acc);
        agg.add(tx(EventTypes.TX_COMMITTED, "tx-2", 0), acc);
        agg.add(tx(EventTypes.TX_ABORTED, "tx-3", 0), acc);

        ClaimsOverview o = agg.getResult(acc);

        assertThat(o.getCountsByStatus()).containsEntry("SUBMITTED", 3L).containsEntry("APPROVED", 3L)
                .containsEntry("REJECTED", 1L).hasSize(3);
        assertThat(o.getApprovalRate()).isEqualTo(0.75);
        assertThat(o.getClaimsCreated()).isEqualTo(3);
        assertThat(o.getTotalClaimed()).isEqualByComparingTo("6000.50");
        assertThat(o.getAvgClaimed()).isEqualByComparingTo("2000.17");
        assertThat(o.getSettlementCommits()).isEqualTo(2);
        assertThat(o.getSettlementAborts()).isEqualTo(1);
    }

    @Test
    void emptyWindowHasZeroRatesWithoutDividingByZero() {
        ClaimsOverview o = agg.getResult(agg.createAccumulator());
        assertThat(o.getApprovalRate()).isZero();
        assertThat(o.getAvgClaimed()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(o.getCountsByStatus()).isEmpty();
    }

    @Test
    void mergeAddsBothSides() {
        var a = agg.createAccumulator();
        agg.add(created("c-1", "P-1", 0, "100"), a);
        agg.add(status("c-1", "APPROVED", 0), a);
        var b = agg.createAccumulator();
        agg.add(created("c-2", "P-1", 0, "300"), b);
        agg.add(status("c-2", "REJECTED", 0), b);
        agg.add(tx(EventTypes.TX_COMMITTED, "tx-1", 0), b);

        ClaimsOverview o = agg.getResult(agg.merge(a, b));

        assertThat(o.getCountsByStatus()).containsEntry("SUBMITTED", 2L);
        assertThat(o.getApprovalRate()).isEqualTo(0.5);
        assertThat(o.getTotalClaimed()).isEqualByComparingTo("400");
        assertThat(o.getAvgClaimed()).isEqualByComparingTo("200");
        assertThat(o.getSettlementCommits()).isEqualTo(1);
    }
}
