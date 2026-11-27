package com.claims.analytics;

import com.claims.analytics.config.JobConfig;
import com.claims.analytics.functions.ClaimsOverviewAggregate;
import com.claims.analytics.functions.ClaimsOverviewWindowFunction;
import com.claims.analytics.functions.FraudCountAggregate;
import com.claims.analytics.functions.FraudFlagWindowFunction;
import com.claims.analytics.functions.SettlementViewFunction;
import com.claims.analytics.model.ClaimEvent;
import com.claims.analytics.model.ClaimsOverview;
import com.claims.analytics.model.EventTypes;
import com.claims.analytics.model.FraudFlag;
import com.claims.analytics.model.SettlementView;
import com.claims.analytics.serde.ClaimEventDeserializer;
import com.claims.analytics.serde.JsonSerializer;
import com.claims.analytics.sinks.DynamoItems;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.aws.config.AWSConfigConstants;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.base.sink.writer.ElementConverter;
import org.apache.flink.connector.dynamodb.sink.DynamoDbSink;
import org.apache.flink.connector.dynamodb.sink.DynamoDbWriteRequest;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Audit and analytics stream processor.
 *
 * <pre>
 *                        ┌─▶ audit trail ─────────────────────────────▶ DynamoDB CLAIM#id / EVENT#ts#eventId
 * Kafka claim-events ──▶─┼─▶ 1 min tumbling  claims overview ──┬─────▶ DynamoDB METRIC#claims_overview#1m
 * (event time,           │                                     └─────▶ Kafka claim-aggregates
 *  10 s out-of-order)    ├─▶ keyBy policyNo, 10 min / 1 min sliding ─▶ fraud flags ─▶ DynamoDB FRAUD# + Kafka claim-analytics
 *                        └─▶ keyBy txId, keyed state (1 day TTL) ───▶ DynamoDB TX#txId / STATE
 * </pre>
 */
public final class AuditAnalyticsJob {

    public static final Duration MAX_OUT_OF_ORDERNESS = Duration.ofSeconds(10);
    /** A Kafka partition with no event for this long lets the watermark follow the wall clock. */
    public static final Duration CLOCK_ADVANCE_AFTER = Duration.ofSeconds(5);
    /** A Kafka partition with no traffic for this long stops holding back the watermark. */
    public static final Duration SOURCE_IDLENESS = Duration.ofMinutes(1);
    public static final Duration OVERVIEW_WINDOW = Duration.ofMinutes(1);
    public static final Duration FRAUD_WINDOW = Duration.ofMinutes(10);
    public static final Duration FRAUD_SLIDE = Duration.ofMinutes(1);
    /** More than this many distinct new claims on one policy inside {@link #FRAUD_WINDOW} raises a flag. */
    public static final int FRAUD_THRESHOLD = 2;
    public static final Duration CHECKPOINT_INTERVAL = Duration.ofSeconds(30);

    private AuditAnalyticsJob() {
    }

    public static void main(String[] args) throws Exception {
        JobConfig config = JobConfig.fromArgs(args);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(checkpointing(config));
        buildPipeline(env, config);
        env.execute("audit-analytics");
    }

    /** Exactly-once checkpoints every 30 s, stored under {@code --checkpoint-dir} (S3 on EMR). */
    static Configuration checkpointing(JobConfig config) {
        Configuration conf = new Configuration();
        conf.set(CheckpointingOptions.CHECKPOINT_STORAGE, "filesystem");
        conf.set(CheckpointingOptions.CHECKPOINTS_DIRECTORY, config.checkpointDir());
        return conf;
    }

    static void buildPipeline(StreamExecutionEnvironment env, JobConfig config) {
        env.enableCheckpointing(CHECKPOINT_INTERVAL.toMillis(), CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(Duration.ofSeconds(5).toMillis());

        KafkaSource<ClaimEvent> source = KafkaSource.<ClaimEvent>builder()
                .setBootstrapServers(config.bootstrap())
                .setTopics(config.eventsTopic())
                .setGroupId(JobConfig.GROUP_ID)
                .setStartingOffsets("earliest".equals(config.startingOffsets())
                        ? OffsetsInitializer.earliest()
                        : OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
                .setValueOnlyDeserializer(new ClaimEventDeserializer())
                .build();

        DataStream<ClaimEvent> events = env
                .fromSource(source, watermarkStrategy(), "claim-events")
                .uid("claim-events-source");

        Outputs out = analytics(events);

        // DynamoDB read models. PUTs on deterministic keys are idempotent, so replays are harmless.
        out.audit().sinkTo(dynamoSink(config, DynamoItems.auditConverter()))
                .name("dynamodb-audit").uid("dynamodb-audit");
        out.overview().sinkTo(dynamoSink(config, DynamoItems.overviewConverter()))
                .name("dynamodb-overview").uid("dynamodb-overview");
        out.fraud().sinkTo(dynamoSink(config, DynamoItems.fraudConverter()))
                .name("dynamodb-fraud").uid("dynamodb-fraud");
        out.settlement().sinkTo(dynamoSink(config, DynamoItems.settlementConverter()))
                .name("dynamodb-settlement").uid("dynamodb-settlement");

        // claim-aggregates topic: one JSON record per closed 1-minute window.
        out.overview().map(new JsonSerializer<ClaimsOverview>()).name("overview-json").uid("overview-json")
                .sinkTo(kafkaSink(config, config.aggregatesTopic()))
                .name("kafka-claim-aggregates").uid("kafka-claim-aggregates");
        // claim-analytics topic: fraud flags (alerts) as JSON.
        out.fraud().map(new JsonSerializer<FraudFlag>()).name("fraud-json").uid("fraud-json")
                .sinkTo(kafkaSink(config, config.resultsTopic()))
                .name("kafka-claim-analytics").uid("kafka-claim-analytics");
    }

    /**
     * Event time = {@code occurredAt}, with 10 s bounded out-of-orderness. A partition that has been quiet for
     * {@link #CLOCK_ADVANCE_AFTER} lets the watermark follow the wall clock, so the last minute's window closes
     * about 10 s after the minute ends even when no later event arrives ({@link IdleAdvancingWatermarks}).
     */
    public static WatermarkStrategy<ClaimEvent> watermarkStrategy() {
        return WatermarkStrategy.<ClaimEvent>forGenerator(ctx ->
                        new IdleAdvancingWatermarks(MAX_OUT_OF_ORDERNESS, CLOCK_ADVANCE_AFTER, System::currentTimeMillis))
                .withTimestampAssigner((event, recordTs) -> event.getOccurredAt())
                .withIdleness(SOURCE_IDLENESS);
    }

    /** The four result streams computed from the timestamped event stream. */
    public record Outputs(
            DataStream<ClaimEvent> audit,
            DataStream<ClaimsOverview> overview,
            DataStream<FraudFlag> fraud,
            DataStream<SettlementView> settlement) {
    }

    /**
     * The analytics topology, independent of Kafka and DynamoDB so tests can feed it a fixed list of events.
     * {@code events} must already carry event-time timestamps and watermarks.
     */
    public static Outputs analytics(DataStream<ClaimEvent> events) {
        DataStream<ClaimEvent> audit = events
                .filter(e -> e.getClaimId() != null).name("auditable-events").uid("auditable-events");

        DataStream<ClaimsOverview> overview = events
                .windowAll(TumblingEventTimeWindows.of(OVERVIEW_WINDOW))
                .aggregate(new ClaimsOverviewAggregate(), new ClaimsOverviewWindowFunction())
                .name("claims-overview-1m").uid("claims-overview-1m");

        DataStream<FraudFlag> fraud = events
                .filter(e -> EventTypes.CLAIM_CREATED.equals(e.getEventType()) && e.getPolicyNo() != null)
                .name("claims-created").uid("claims-created")
                .keyBy(ClaimEvent::getPolicyNo)
                .window(SlidingEventTimeWindows.of(FRAUD_WINDOW, FRAUD_SLIDE))
                .aggregate(new FraudCountAggregate(),
                        new FraudFlagWindowFunction(FRAUD_THRESHOLD, (int) FRAUD_WINDOW.toMinutes()))
                .name("fraud-detection").uid("fraud-detection");

        DataStream<SettlementView> settlement = events
                .filter(ClaimEvent::isTxEvent).name("tx-events").uid("tx-events")
                .keyBy(ClaimEvent::getTxId)
                .process(new SettlementViewFunction())
                .name("settlement-view").uid("settlement-view");

        return new Outputs(audit, overview, fraud, settlement);
    }

    private static <T> DynamoDbSink<T> dynamoSink(JobConfig config,
                                                  ElementConverter<T, DynamoDbWriteRequest> converter) {
        Properties props = new Properties();
        props.setProperty(AWSConfigConstants.AWS_REGION, config.region());
        if (config.dynamoDbEndpoint() != null) {
            props.setProperty(AWSConfigConstants.AWS_ENDPOINT, config.dynamoDbEndpoint());
        }
        return DynamoDbSink.<T>builder()
                .setTableName(config.table())
                .setElementConverter(converter)
                // de-duplicate two writes to the same item inside one batch (BatchWriteItem rejects that)
                .setOverwriteByPartitionKeys(List.of("PK", "SK"))
                .setDynamoDbProperties(props)
                .build();
    }

    private static KafkaSink<String> kafkaSink(JobConfig config, String topic) {
        return KafkaSink.<String>builder()
                .setBootstrapServers(config.bootstrap())
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(topic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }
}
