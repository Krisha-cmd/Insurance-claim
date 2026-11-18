package com.claims.approval.events;

import com.claims.common.event.ClaimEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** JSON value, claim id as the key (keeps one claim's events in order on one partition). */
public class KafkaAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;

    public KafkaAuditPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, String topic) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
    }

    @Override
    public void publish(ClaimEvent event) {
        send(event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Could not publish {} for claim {}: {}", event.eventType(), event.claimId(), ex.getMessage());
            }
        });
    }

    @Override
    public void publishAndWait(ClaimEvent event, Duration timeout) {
        try {
            send(event).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not publish {} for claim {}: {}", event.eventType(), event.claimId(), e.getMessage());
        }
    }

    private CompletableFuture<?> send(ClaimEvent event) {
        try {
            return kafka.send(topic, event.claimId().toString(), mapper.writeValueAsString(event));
        } catch (JsonProcessingException | RuntimeException e) { // RuntimeException: e.g. no metadata within max.block.ms
            return CompletableFuture.failedFuture(e);
        }
    }
}
