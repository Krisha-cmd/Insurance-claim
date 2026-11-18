package com.claims.analytics.functions;

import org.junit.jupiter.api.Test;

import static com.claims.analytics.Events.created;
import static org.assertj.core.api.Assertions.assertThat;

class FraudCountAggregateTest {

    private final FraudCountAggregate agg = new FraudCountAggregate();

    @Test
    void countsDistinctClaimsOnly() {
        var acc = agg.createAccumulator();
        agg.add(created("c-2", "P-1", 0, "1"), acc);
        agg.add(created("c-1", "P-1", 0, "1"), acc);
        agg.add(created("c-1", "P-1", 0, "1"), acc); // replayed event

        assertThat(agg.getResult(acc)).containsExactly("c-1", "c-2");
    }

    @Test
    void mergeIsAUnion() {
        var a = agg.createAccumulator();
        agg.add(created("c-1", "P-1", 0, "1"), a);
        var b = agg.createAccumulator();
        agg.add(created("c-1", "P-1", 0, "1"), b);
        agg.add(created("c-3", "P-1", 0, "1"), b);

        assertThat(agg.getResult(agg.merge(a, b))).containsExactly("c-1", "c-3");
    }
}
