package com.claims.analytics.config;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Job settings. Each value is read from a job argument first, then an environment variable, then a default.
 *
 * <pre>
 * --bootstrap        (alias --bootstrap-servers)  env KAFKA_BOOTSTRAP     default localhost:9092
 * --events-topic     (alias --source-topic)       env EVENTS_TOPIC        default claim-events
 * --results-topic                                 env RESULTS_TOPIC       default claim-analytics
 * --table                                         env ANALYTICS_TABLE     default claims-dev-analytics
 * --region                                        env AWS_REGION          default ap-south-1
 * --checkpoint-dir                                env CHECKPOINT_DIR      default file:///tmp/audit-flink/checkpoints
 * --dynamodb-endpoint (optional, local only)      env DYNAMODB_ENDPOINT   default none (real AWS endpoint)
 * </pre>
 */
public record JobConfig(
        String bootstrap,
        String eventsTopic,
        String resultsTopic,
        String table,
        String region,
        String checkpointDir,
        String dynamoDbEndpoint) implements Serializable {

    public static final String GROUP_ID = "audit-flink";

    public static JobConfig fromArgs(String[] args) {
        return fromArgs(args, System::getenv);
    }

    static JobConfig fromArgs(String[] args, Function<String, String> env) {
        Map<String, String> a = parse(args);
        return new JobConfig(
                pick(a, env, List.of("bootstrap", "bootstrap-servers"), "KAFKA_BOOTSTRAP", "localhost:9092"),
                pick(a, env, List.of("events-topic", "source-topic"), "EVENTS_TOPIC", "claim-events"),
                pick(a, env, List.of("results-topic"), "RESULTS_TOPIC", "claim-analytics"),
                pick(a, env, List.of("table"), "ANALYTICS_TABLE", "claims-dev-analytics"),
                pick(a, env, List.of("region"), "AWS_REGION", "ap-south-1"),
                pick(a, env, List.of("checkpoint-dir"), "CHECKPOINT_DIR", "file:///tmp/audit-flink/checkpoints"),
                pick(a, env, List.of("dynamodb-endpoint"), "DYNAMODB_ENDPOINT", null));
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String key = arg.substring(2);
            String value;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                value = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            } else {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            out.put(key, value);
        }
        return out;
    }

    private static String pick(Map<String, String> args, Function<String, String> env,
                               List<String> names, String envName, String fallback) {
        for (String name : names) {
            String v = args.get(name);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        String v = env.apply(envName);
        return v != null && !v.isBlank() ? v : fallback;
    }
}
