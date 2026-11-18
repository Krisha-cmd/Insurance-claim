package com.claims.docverify;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.PrintStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point: {@code com.claims.docverify.Handler::handleRequest} (see infra/terraform/modules/lambda).
 *
 * <p>For each SQS message (an S3 event notification) and each created object in it:
 * HeadObject → rules → one VERIFIED/REJECTED result on RESULTS_QUEUE_URL.
 * <ul>
 *   <li>A failed rule is a REJECTED result, never a failure.</li>
 *   <li>An S3 or SQS error fails the whole message (it goes into {@code batchItemFailures}, so only that
 *       message is retried; after 3 receives it lands in the upload queue's DLQ). Results already sent for
 *       that message are sent again on the retry, which Claim Intake ignores (it is idempotent per document).</li>
 *   <li>A body that is not an S3 event, or a key that is not a claim document, is logged and dropped:
 *       retrying cannot fix it.</li>
 * </ul>
 * Clients and collaborators are built once, when the class is loaded, so SnapStart snapshots them.
 */
public class Handler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final S3EventParser parser;
    private final DocumentValidator validator;
    private final ResultPublisher publisher;
    private final S3Client s3;
    private final Config config;
    private final PrintStream log;

    /** Used by the Lambda runtime. */
    public Handler() {
        this(Config.fromEnv());
    }

    private Handler(Config config) {
        this(config,
                S3Client.builder().region(Region.of(config.region()))
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                SqsClient.builder().region(Region.of(config.region()))
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                Clock.systemUTC(), System.out);
    }

    /** For tests (and anyone wiring their own clients). */
    public Handler(Config config, S3Client s3, SqsClient sqs, Clock clock, PrintStream log) {
        this.config = config;
        this.s3 = s3;
        this.parser = new S3EventParser(JSON);
        this.validator = DocumentValidator.standard(config, clock);
        this.publisher = new ResultPublisher(sqs, JSON, config.resultsQueueUrl());
        this.log = log;
        prime();
    }

    /**
     * Runs the parsing, validation and serialisation code once during init, so the classes are loaded and
     * Jackson's serializers are built before SnapStart takes its snapshot (no network calls).
     */
    private void prime() {
        try {
            String sample = "{\"Records\":[{\"eventName\":\"ObjectCreated:Put\",\"s3\":{\"bucket\":{\"name\":\"b\"},"
                    + "\"object\":{\"key\":\"claims/00000000-0000-0000-0000-000000000000/FINAL_BILL/"
                    + "00000000-0000-0000-0000-000000000001-x.pdf\",\"size\":1}}}]}";
            ObjectKey key = parser.parse(sample).getFirst();
            VerificationResult r = validator.validate(key, HeadObjectResponse.builder()
                    .contentType("application/pdf").contentLength(1L).eTag("\"e\"").build());
            JSON.writeValueAsString(r);
        } catch (Exception e) {
            log.println("{\"status\":\"PRIME_FAILED\",\"error\":\"" + e.getClass().getSimpleName() + "\"}");
        }
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        if (event == null || event.getRecords() == null) {
            return new SQSBatchResponse(failures);
        }
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                handleMessage(message);
            } catch (SdkException e) {
                logLine("RETRY", message.getMessageId(), null, Map.of("error", String.valueOf(e.getMessage())));
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            } catch (IllegalArgumentException e) {
                logLine("DROPPED", message.getMessageId(), null, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        return new SQSBatchResponse(failures);
    }

    private void handleMessage(SQSEvent.SQSMessage message) {
        for (ObjectKey key : parser.parse(message.getBody())) {
            long start = System.nanoTime();
            if (config.documentsBucket() != null && !config.documentsBucket().equals(key.bucket())) {
                logLine("IGNORED", message.getMessageId(), key, Map.of("error", "not the documents bucket"));
                continue;
            }
            if (key.documentId() == null) {
                // No document id means no claim document to report on: nothing would read the result.
                logLine("IGNORED", message.getMessageId(), key, Map.of("error", "not a claims/... document key"));
                continue;
            }
            VerificationResult result = verify(key);
            publisher.publish(result);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reason", result.reason());
            extra.put("durationMs", (System.nanoTime() - start) / 1_000_000);
            logLine(result.status().name(), message.getMessageId(), key, extra);
        }
    }

    private VerificationResult verify(ObjectKey key) {
        HeadObjectResponse head;
        try {
            head = s3.headObject(HeadObjectRequest.builder().bucket(key.bucket()).key(key.key()).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return validator.reject(key, "The uploaded file no longer exists");
            }
            throw e; // 403 (no ListBucket, so also a missing object), throttling, 5xx: retry
        }
        return validator.validate(key, head);
    }

    /** One JSON line per document (CloudWatch Logs Insights can filter on the fields). */
    private void logLine(String status, String messageId, ObjectKey key, Map<String, Object> extra) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("status", status);
        line.put("messageId", messageId);
        if (key != null) {
            line.put("claimId", key.claimId());
            line.put("documentId", key.documentId());
            line.put("docType", key.docType());
            line.put("key", key.key());
        }
        line.putAll(extra);
        try {
            log.println(JSON.writeValueAsString(line));
        } catch (JsonProcessingException e) {
            log.println(line);
        }
    }
}
