package com.claims.docverify;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.claims.docverify.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The handler with mocked S3 and SQS clients, driven by real S3 event fixtures. */
class HandlerTest {

    private static final String QUEUE = "https://sqs.ap-south-1.amazonaws.com/123456789012/claims-dev-doc-verification-results";
    private static final long MB = 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private S3Client s3;
    private SqsClient sqs;
    private ByteArrayOutputStream logs;
    private Handler handler;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        sqs = mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().messageId("m").build());
        logs = new ByteArrayOutputStream();
        Config config = Config.from(Map.of("RESULTS_QUEUE_URL", QUEUE, "MAX_SIZE_MB", "10",
                "ALLOWED_TYPES", "application/pdf,image/jpeg,image/png", "DOCUMENTS_BUCKET", Fixtures.BUCKET));
        handler = new Handler(config, s3, sqs, Clock.fixed(Instant.parse("2026-11-18T10:00:01Z"), ZoneOffset.UTC),
                new PrintStream(logs, true, StandardCharsets.UTF_8));
    }

    private static SQSEvent sqsEvent(String... bodies) {
        List<SQSEvent.SQSMessage> messages = new ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            SQSEvent.SQSMessage m = new SQSEvent.SQSMessage();
            m.setMessageId("msg-" + (i + 1));
            m.setBody(bodies[i]);
            messages.add(m);
        }
        SQSEvent e = new SQSEvent();
        e.setRecords(messages);
        return e;
    }

    private void headReturns(String keyPart, String contentType, long size) {
        when(s3.headObject(argThat((HeadObjectRequest r) -> r != null && r.key().contains(keyPart))))
                .thenReturn(HeadObjectResponse.builder().contentType(contentType).contentLength(size).eTag("\"etag1\"").build());
    }

    private List<JsonNode> sentResults() throws Exception {
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, org.mockito.Mockito.atLeast(0)).sendMessage(sent.capture());
        List<JsonNode> out = new ArrayList<>();
        for (SendMessageRequest r : sent.getAllValues()) {
            assertThat(r.queueUrl()).isEqualTo(QUEUE);
            out.add(json.readTree(r.messageBody()));
        }
        return out;
    }

    @Test
    void aPdfPasses() throws Exception {
        headReturns("FINAL_BILL", "application/pdf", 48213);

        SQSBatchResponse res = handler.handleRequest(sqsEvent(event("put-pdf.json")), null);

        assertThat(res.getBatchItemFailures()).isEmpty();
        JsonNode r = sentResults().getFirst();
        assertThat(r.get("claimId").asText()).isEqualTo(Fixtures.CLAIM.toString());
        assertThat(r.get("documentId").asText()).isEqualTo(Fixtures.BILL.toString());
        assertThat(r.get("docType").asText()).isEqualTo("FINAL_BILL");
        assertThat(r.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(r.get("reason").isNull()).isTrue();
        assertThat(r.get("checkedAt").asText()).isEqualTo("2026-11-18T10:00:01Z");
        assertThat(r.get("s3Key").asText()).endsWith("-final_bill.pdf");
        assertThat(r.get("checksum").asText()).isEqualTo("etag:etag1");

        // One JSON log line per document.
        JsonNode line = json.readTree(logs.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains("VERIFIED")).findFirst().orElseThrow());
        assertThat(line.get("claimId").asText()).isEqualTo(Fixtures.CLAIM.toString());
        assertThat(line.get("documentId").asText()).isEqualTo(Fixtures.BILL.toString());
        assertThat(line.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(line.get("durationMs").isNumber()).isTrue();
    }

    @Test
    void anExeIsRejected_notAFailure() throws Exception {
        headReturns("ID_PROOF", "application/x-msdownload", 1048576);

        SQSBatchResponse res = handler.handleRequest(sqsEvent(event("put-exe.json")), null);

        assertThat(res.getBatchItemFailures()).isEmpty();
        JsonNode r = sentResults().getFirst();
        assertThat(r.get("status").asText()).isEqualTo("REJECTED");
        assertThat(r.get("reason").asText()).isEqualTo("File type application/x-msdownload is not allowed; upload a PDF, JPEG or PNG");
    }

    @Test
    void anOversizeFileIsRejected() throws Exception {
        headReturns("DISCHARGE_SUMMARY", "application/pdf", 15 * MB);

        SQSBatchResponse res = handler.handleRequest(sqsEvent(event("put-oversize.json")), null);

        assertThat(res.getBatchItemFailures()).isEmpty();
        JsonNode r = sentResults().getFirst();
        assertThat(r.get("status").asText()).isEqualTo("REJECTED");
        assertThat(r.get("reason").asText()).isEqualTo("The file is 15.0 MB; the limit is 10 MB");
    }

    @Test
    void anS3ErrorGoesIntoBatchItemFailures_andTheOtherMessagesStillSucceed() throws Exception {
        when(s3.headObject(argThat((HeadObjectRequest r) -> r != null && r.key().contains("ID_PROOF"))))
                .thenThrow(S3Exception.builder().statusCode(503).message("Slow Down").build());
        headReturns("FINAL_BILL", "application/pdf", 48213);

        SQSBatchResponse res = handler.handleRequest(sqsEvent(event("put-exe.json"), event("put-pdf.json")), null);

        assertThat(res.getBatchItemFailures()).extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .containsExactly("msg-1");
        assertThat(sentResults()).singleElement().satisfies(r -> assertThat(r.get("docType").asText()).isEqualTo("FINAL_BILL"));
        assertThat(logs.toString(StandardCharsets.UTF_8)).contains("\"status\":\"RETRY\"").contains("\"messageId\":\"msg-1\"");
    }

    @Test
    void anSqsErrorAlsoRetriesTheMessage() {
        headReturns("FINAL_BILL", "application/pdf", 48213);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(SqsException.builder().statusCode(500).message("boom").build());

        assertThat(handler.handleRequest(sqsEvent(event("put-pdf.json")), null).getBatchItemFailures()).hasSize(1);
    }

    @Test
    void aNetworkErrorRetriesTheMessage() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create("connection reset"));
        assertThat(handler.handleRequest(sqsEvent(event("put-pdf.json")), null).getBatchItemFailures()).hasSize(1);
    }

    @Test
    void aDeletedObjectIsRejected() throws Exception {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());

        assertThat(handler.handleRequest(sqsEvent(event("put-pdf.json")), null).getBatchItemFailures()).isEmpty();
        assertThat(sentResults().getFirst().get("reason").asText()).isEqualTo("The uploaded file no longer exists");
    }

    @Test
    void anUnknownDocTypeIsRejected() throws Exception {
        headReturns("SELFIE", "image/jpeg", 1024);
        handler.handleRequest(sqsEvent(event("put-unknown-doctype.json")), null);
        assertThat(sentResults().getFirst().get("reason").asText()).isEqualTo("Unknown document type SELFIE");
    }

    @Test
    void everyObjectInAMessageGetsAResult() throws Exception {
        headReturns("PRESCRIPTION", "image/png", 4096);
        headReturns("FINAL_BILL", "application/pdf", 48213);
        handler.handleRequest(sqsEvent(event("put-two-objects.json")), null);
        assertThat(sentResults()).extracting(r -> r.get("docType").asText()).containsExactly("PRESCRIPTION", "FINAL_BILL");
    }

    @Test
    void testEventsForeignKeysAndGarbageAreDroppedWithoutRetry() throws Exception {
        SQSBatchResponse res = handler.handleRequest(sqsEvent(event("test-event.json"), event("put-bad-key.json"), "not json",
                event("object-removed.json")), null);

        assertThat(res.getBatchItemFailures()).isEmpty();
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
        assertThat(sentResults()).isEmpty();
        assertThat(logs.toString(StandardCharsets.UTF_8)).contains("\"status\":\"IGNORED\"").contains("\"status\":\"DROPPED\"");
    }

    @Test
    void anotherBucketIsIgnored() throws Exception {
        String other = event("put-pdf.json").replace(Fixtures.BUCKET, "someone-elses-bucket");
        assertThat(handler.handleRequest(sqsEvent(other), null).getBatchItemFailures()).isEmpty();
        assertThat(sentResults()).isEmpty();
    }

    @Test
    void anEmptyEventIsFine() {
        assertThat(handler.handleRequest(new SQSEvent(), null).getBatchItemFailures()).isEmpty();
    }
}
