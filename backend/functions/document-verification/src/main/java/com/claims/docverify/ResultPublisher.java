package com.claims.docverify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Sends a result to RESULTS_QUEUE_URL as JSON. SQS errors propagate (the handler retries the message). */
public class ResultPublisher {

    private final SqsClient sqs;
    private final ObjectMapper json;
    private final String queueUrl;

    public ResultPublisher(SqsClient sqs, ObjectMapper json, String queueUrl) {
        this.sqs = sqs;
        this.json = json;
        this.queueUrl = queueUrl;
    }

    public void publish(VerificationResult result) {
        String body;
        try {
            body = json.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build());
    }
}
