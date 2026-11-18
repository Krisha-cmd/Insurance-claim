package com.claims.analytics;

import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.ClaimsOverview;
import com.claims.analytics.model.EventTypes;
import com.claims.analytics.model.FraudFlag;
import com.claims.analytics.model.SettlementView;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.claims.analytics.Events.at;
import static com.claims.analytics.Events.created;
import static com.claims.analytics.Events.status;
import static com.claims.analytics.Events.tx;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs the whole analytics topology on a Flink MiniCluster over a fixed, timestamped list of events. */
class AuditAnalyticsPipelineTest {

    @RegisterExtension
    static final MiniClusterExtension FLINK = new MiniClusterExtension(
            new MiniClusterResourceConfiguration.Builder()
                    .setNumberTaskManagers(1)
                    .setNumberSlotsPerTaskManager(2)
                    .build());

    static final ConcurrentLinkedQueue<ClaimEvent> AUDIT = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<ClaimsOverview> OVERVIEW = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<FraudFlag> FRAUD = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<SettlementView> SETTLEMENT = new ConcurrentLinkedQueue<>();

    static List<ClaimEvent> input;

    @BeforeAll
    static void runPipeline() throws Exception {
        input = new ArrayList<>();
        // window 14:00-14:01
        input.add(created("c-1", "P-A", at(0, 5), "1000"));
        input.add(created("c-2", "P-B", at(0, 10), "2000"));
        input.add(status("c-1", "APPROVED", at(0, 30)));
        input.add(status("c-2", "REJECTED", at(0, 25))); // 5 s out of order: within the 10 s bound
        input.add(tx(EventTypes.TX_STARTED, "tx-1", at(0, 40)));
        input.add(tx(EventTypes.TX_COMMITTED, "tx-1", at(0, 50)));
        // window 14:01-14:02
        input.add(created("c-3", "P-A", at(1, 10), "500")); // P-A: only 2 claims, never flagged
        input.add(tx(EventTypes.TX_ABORTED, "tx-2", at(1, 20)));
        // P-FRAUD: 3 claims within 6 minutes -> flagged
        input.add(created("f-1", "P-FRAUD", at(2, 0), "100"));
        input.add(created("s-1", "P-SPREAD", at(3, 0), "100"));
        input.add(created("f-2", "P-FRAUD", at(5, 0), "100"));
        input.add(created("f-3", "P-FRAUD", at(8, 0), "100"));
        // P-SPREAD: 3 claims spread over 15 minutes -> never 3 inside one 10 minute window
        input.add(created("s-2", "P-SPREAD", at(10, 30), "100"));
        input.add(created("s-3", "P-SPREAD", at(18, 0), "100"));
        // an event without a claim is not audited
        input.add(Events.event("HEARTBEAT", at(18, 30)));

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        DataStream<ClaimEvent> events = env.fromData(input).setParallelism(1)
                .assignTimestampsAndWatermarks(AuditAnalyticsJob.watermarkStrategy()).setParallelism(1);

        AuditAnalyticsJob.Outputs out = AuditAnalyticsJob.analytics(events);
        out.audit().addSink(new Collect<>("audit"));
        out.overview().addSink(new Collect<>("overview"));
        out.fraud().addSink(new Collect<>("fraud"));
        out.settlement().addSink(new Collect<>("settlement"));
        env.execute("pipeline-test");
    }

    @Test
    void auditsEveryEventThatBelongsToAClaim() {
        assertThat(AUDIT).hasSize(input.size() - 1);
        assertThat(AUDIT).noneMatch(e -> e.getEventType().equals("HEARTBEAT"));
    }

    @Test
    void tumblingWindowAggregatesTheFirstMinute() {
        ClaimsOverview first = overviewStartingAt(at(0, 0));

        assertThat(first.getWindowEnd()).isEqualTo(at(1, 0));
        assertThat(first.getCountsByStatus()).containsEntry("SUBMITTED", 2L)
                .containsEntry("APPROVED", 1L).containsEntry("REJECTED", 1L).hasSize(3);
        assertThat(first.getApprovalRate()).isEqualTo(0.5);
        assertThat(first.getClaimsCreated()).isEqualTo(2);
        assertThat(first.getTotalClaimed()).isEqualByComparingTo("3000");
        assertThat(first.getAvgClaimed()).isEqualByComparingTo("1500");
        assertThat(first.getSettlementCommits()).isEqualTo(1);
        assertThat(first.getSettlementAborts()).isZero();
    }

    @Test
    void tumblingWindowsDoNotOverlap() {
        ClaimsOverview second = overviewStartingAt(at(1, 0));
        assertThat(second.getClaimsCreated()).isEqualTo(1);
        assertThat(second.getTotalClaimed()).isEqualByComparingTo("500");
        assertThat(second.getSettlementAborts()).isEqualTo(1);
        assertThat(second.getSettlementCommits()).isZero();

        long created = OVERVIEW.stream().mapToLong(ClaimsOverview::getClaimsCreated).sum();
        assertThat(created).isEqualTo(input.stream()
                .filter(e -> e.getEventType().equals(EventTypes.CLAIM_CREATED)).count());
    }

    @Test
    void flagsThreeClaimsOnOnePolicyWithinTenMinutes() {
        List<FraudFlag> flags = FRAUD.stream().filter(f -> f.getPolicyNo().equals("P-FRAUD"))
                .sorted(Comparator.comparingLong(FraudFlag::getWindowEnd)).toList();

        // Sliding windows [s, s+10m) every minute that hold 14:02, 14:05 and 14:08: s = 13:59 .. 14:02
        assertThat(flags).hasSize(4);
        assertThat(flags).allSatisfy(f -> {
            assertThat(f.getClaimCount()).isEqualTo(3);
            assertThat(f.getClaimIds()).containsExactly("f-1", "f-2", "f-3");
            assertThat(f.getWindowMinutes()).isEqualTo(10);
            assertThat(f.getWindowEnd() - f.getWindowStart()).isEqualTo(10 * 60_000L);
        });
        assertThat(flags.get(0).getWindowEnd()).isEqualTo(at(9, 0));
        assertThat(flags.get(3).getWindowEnd()).isEqualTo(at(12, 0));
    }

    @Test
    void doesNotFlagClaimsSpreadOverFifteenMinutesOrOnlyTwoClaims() {
        assertThat(FRAUD).noneMatch(f -> f.getPolicyNo().equals("P-SPREAD"));
        assertThat(FRAUD).noneMatch(f -> f.getPolicyNo().equals("P-A"));
        assertThat(FRAUD).allMatch(f -> f.getPolicyNo().equals("P-FRAUD"));
    }

    @Test
    void settlementViewEndsCommitted() {
        List<SettlementView> tx1 = SETTLEMENT.stream().filter(v -> v.getTxId().equals("tx-1"))
                .sorted(Comparator.comparingLong(SettlementView::getUpdatedAt)).toList();
        assertThat(tx1).hasSize(2);
        assertThat(tx1.get(1).getOutcome()).isEqualTo("COMMITTED");
        assertThat(tx1.get(1).getCoordinator()).isEqualTo("approval");
    }

    private static ClaimsOverview overviewStartingAt(long start) {
        return OVERVIEW.stream().filter(o -> o.getWindowStart() == start).findFirst()
                .orElseThrow(() -> new AssertionError("no overview window at " + start + " in " + OVERVIEW));
    }

    /**
     * Collects into one of the static queues (the MiniCluster runs in this JVM). The sink is serialized when the
     * job is submitted, so it holds the queue's name, not the queue itself.
     */
    @SuppressWarnings({"deprecation", "unchecked"})
    static class Collect<T> implements SinkFunction<T> {
        private final String queue;

        Collect(String queue) {
            this.queue = queue;
        }

        @Override
        public void invoke(T value, Context context) {
            ConcurrentLinkedQueue<Object> target = (ConcurrentLinkedQueue<Object>) switch (queue) {
                case "audit" -> (ConcurrentLinkedQueue<?>) AUDIT;
                case "overview" -> OVERVIEW;
                case "fraud" -> FRAUD;
                case "settlement" -> SETTLEMENT;
                default -> throw new IllegalArgumentException(queue);
            };
            target.add(value);
        }
    }
}
