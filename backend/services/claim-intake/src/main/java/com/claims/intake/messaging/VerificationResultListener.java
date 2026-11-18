package com.claims.intake.messaging;

import com.claims.intake.config.IntakeProperties;
import com.claims.intake.service.DocumentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;

/**
 * Long-polls RESULTS_QUEUE_URL (up to 10 messages, 20 s wait) on its own thread. A message is deleted only
 * after its result is committed; anything that fails stays on the queue, comes back after the visibility
 * timeout and goes to the DLQ after 3 receives (the queue's redrive policy). Applying a result is idempotent
 * on the document id, so a redelivered message is harmless.
 */
@Component
public class VerificationResultListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(VerificationResultListener.class);

    private final SqsClient sqs;
    private final DocumentService documents;
    private final ObjectMapper json;
    private final IntakeProperties.Results config;

    private volatile boolean running;
    private Thread thread;

    public VerificationResultListener(SqsClient sqs, DocumentService documents, ObjectMapper json, IntakeProperties props) {
        this.sqs = sqs;
        this.documents = documents;
        this.json = json;
        this.config = props.results();
    }

    @Override
    public void start() {
        if (config.queueUrl() == null || config.queueUrl().isBlank()) {
            log.warn("RESULTS_QUEUE_URL is empty: document verification results will not be read");
            return;
        }
        running = true;
        thread = new Thread(this::loop, "verification-results");
        thread.setDaemon(true);
        thread.start();
        log.info("Listening for verification results on {}", config.queueUrl());
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                poll();
            } catch (Exception e) {
                if (!running) return;
                log.warn("Polling {} failed: {}; retrying in 5 s", config.queueUrl(), e.getMessage());
                sleep(5000);
            }
        }
    }

    /** One receive call and its messages. */
    public void poll() {
        List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(config.queueUrl())
                .maxNumberOfMessages(config.maxMessages())
                .waitTimeSeconds(config.waitSeconds())).messages();
        for (Message m : messages) {
            handle(m);
        }
    }

    private void handle(Message m) {
        VerificationResultMessage result;
        try {
            result = json.readValue(m.body(), VerificationResultMessage.class);
        } catch (JsonProcessingException e) {
            log.error("Unreadable verification result {} left for the DLQ: {}", m.messageId(), e.getOriginalMessage());
            return;
        }
        try {
            DocumentService.Outcome outcome = documents.applyResult(result);
            sqs.deleteMessage(b -> b.queueUrl(config.queueUrl()).receiptHandle(m.receiptHandle()));
            log.info("Verification result {} for document {}: {}", result.status(), result.documentId(), outcome);
        } catch (DocumentService.UnknownDocumentException | IllegalArgumentException e) {
            log.error("Verification result {} cannot be applied ({}); left for the DLQ", m.messageId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Verification result {} failed ({}); it will be redelivered", m.messageId(), e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
