package com.claims.analytics.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobConfigTest {

    @Test
    void argumentsWinOverEnvironmentAndDefaults() {
        JobConfig c = JobConfig.fromArgs(
                new String[]{"--bootstrap", "b-1:9092", "--table=t1", "--checkpoint-dir", "s3://a/flink/checkpoints"},
                Map.of("KAFKA_BOOTSTRAP", "env:9092", "AWS_REGION", "us-east-1")::get);

        assertThat(c.bootstrap()).isEqualTo("b-1:9092");
        assertThat(c.table()).isEqualTo("t1");
        assertThat(c.region()).isEqualTo("us-east-1");
        assertThat(c.checkpointDir()).isEqualTo("s3://a/flink/checkpoints");
        assertThat(c.eventsTopic()).isEqualTo("claim-events");
        assertThat(c.resultsTopic()).isEqualTo("claim-analytics");
        assertThat(c.aggregatesTopic()).isEqualTo("claim-aggregates");
        assertThat(c.startingOffsets()).isEqualTo("committed");
        assertThat(c.dynamoDbEndpoint()).isNull();
    }

    @Test
    void readsTheAggregatesTopicAndStartingOffsets() {
        JobConfig c = JobConfig.fromArgs(
                new String[]{"--aggregates-topic", "aggs", "--starting-offsets", "EARLIEST"}, k -> null);

        assertThat(c.aggregatesTopic()).isEqualTo("aggs");
        assertThat(c.startingOffsets()).isEqualTo("earliest");
    }

    @Test
    void rejectsUnknownStartingOffsets() {
        assertThatThrownBy(() -> JobConfig.fromArgs(new String[]{"--starting-offsets", "latest"}, k -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTheInfraReadmeArgumentNames() {
        JobConfig c = JobConfig.fromArgs(
                new String[]{"--bootstrap-servers", "b-2:9092", "--source-topic", "events", "--results-topic", "out"},
                k -> null);

        assertThat(c.bootstrap()).isEqualTo("b-2:9092");
        assertThat(c.eventsTopic()).isEqualTo("events");
        assertThat(c.resultsTopic()).isEqualTo("out");
    }

    @Test
    void rejectsAFlagWithoutAValue() {
        assertThatThrownBy(() -> JobConfig.fromArgs(new String[]{"--table"}, k -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
