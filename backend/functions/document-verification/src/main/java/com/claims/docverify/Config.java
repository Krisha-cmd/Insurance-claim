package com.claims.docverify;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings from the Lambda environment (set by infra/terraform/modules/lambda):
 * RESULTS_QUEUE_URL (required), ALLOWED_TYPES (comma-separated MIME types), MAX_SIZE_MB, DOCUMENTS_BUCKET
 * (optional; when set, events for other buckets are ignored). AWS_REGION is set by the Lambda runtime.
 */
public record Config(String resultsQueueUrl, Set<String> allowedTypes, long maxSizeBytes, String documentsBucket,
                     String region) {

    public static final Set<String> DOC_TYPES =
            Set.of("DISCHARGE_SUMMARY", "FINAL_BILL", "PRESCRIPTION", "LAB_REPORT", "ID_PROOF");

    public static Config fromEnv() {
        return from(System.getenv());
    }

    public static Config from(Map<String, String> env) {
        String queue = env.get("RESULTS_QUEUE_URL");
        if (queue == null || queue.isBlank()) {
            throw new IllegalStateException("RESULTS_QUEUE_URL is not set");
        }
        String types = env.getOrDefault("ALLOWED_TYPES", "application/pdf,image/jpeg,image/png");
        long maxMb = Long.parseLong(env.getOrDefault("MAX_SIZE_MB", "10").trim());
        return new Config(queue.trim(), parseTypes(types), maxMb * 1024 * 1024,
                blankToNull(env.get("DOCUMENTS_BUCKET")), env.getOrDefault("AWS_REGION", "ap-south-1"));
    }

    public long maxSizeMb() {
        return maxSizeBytes / (1024 * 1024);
    }

    static Set<String> parseTypes(String csv) {
        return Arrays.stream(csv.split(",")).map(t -> t.trim().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
