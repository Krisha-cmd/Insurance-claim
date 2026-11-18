package com.claims.analytics.functions;

import com.claims.analytics.model.ClaimsOverview;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/** Stamps the pre-aggregated overview with its window boundaries. */
public class ClaimsOverviewWindowFunction
        extends ProcessAllWindowFunction<ClaimsOverview, ClaimsOverview, TimeWindow> {

    @Override
    public void process(Context ctx, Iterable<ClaimsOverview> results, Collector<ClaimsOverview> out) {
        for (ClaimsOverview o : results) {
            o.setWindowStart(ctx.window().getStart());
            o.setWindowEnd(ctx.window().getEnd());
            out.collect(o);
        }
    }
}
