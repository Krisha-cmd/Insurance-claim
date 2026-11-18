package com.claims.analytics.functions;

import com.claims.analytics.model.FraudFlag;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.util.List;

/** Emits a {@link FraudFlag} when a policy's distinct claim count in the window is above the threshold. */
public class FraudFlagWindowFunction
        extends ProcessWindowFunction<List<String>, FraudFlag, String, TimeWindow> {

    private final int threshold;
    private final int windowMinutes;

    /**
     * @param threshold     a flag is raised when the count is strictly greater than this
     * @param windowMinutes window size, copied into the flag for the console
     */
    public FraudFlagWindowFunction(int threshold, int windowMinutes) {
        this.threshold = threshold;
        this.windowMinutes = windowMinutes;
    }

    @Override
    public void process(String policyNo, Context ctx, Iterable<List<String>> results, Collector<FraudFlag> out) {
        for (List<String> claimIds : results) {
            if (claimIds.size() > threshold) {
                out.collect(new FraudFlag(policyNo, ctx.window().getStart(), ctx.window().getEnd(),
                        windowMinutes, claimIds));
            }
        }
    }
}
