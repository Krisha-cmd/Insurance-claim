package com.claims.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Kafka when {@code KAFKA_BOOTSTRAP} is set (MSK enabled), otherwise events only go to the log.
 * Usage in a service's config:
 * <pre>
 * &#64;Bean EventPublisher eventPublisher(&#64;Value("${claims.events.kafka-bootstrap:}") String bootstrap,
 *                                     &#64;Value("${claims.events.topic}") String topic,
 *                                     ObjectProvider&lt;KafkaTemplate&lt;String, String&gt;&gt; kafka, ObjectMapper mapper) {
 *     return EventPublishers.create(bootstrap, topic, kafka, mapper);
 * }
 * </pre>
 */
public final class EventPublishers {

    private static final Logger log = LoggerFactory.getLogger(EventPublishers.class);

    private EventPublishers() {
    }

    public static EventPublisher create(String bootstrap, String topic,
                                        ObjectProvider<KafkaTemplate<String, String>> kafka, ObjectMapper mapper) {
        if (bootstrap != null && !bootstrap.isBlank()) {
            log.info("Publishing claim events to Kafka topic {} at {}", topic, bootstrap);
            return new Kafka(kafka.getObject(), mapper, topic);
        }
        log.info("KAFKA_BOOTSTRAP is empty: claim events are written to the log only");
        return new Logging();
    }

    /** JSON value, claim id as the key (keeps one claim's events in order on one partition). */
    public static final class Kafka implements EventPublisher {

        private final KafkaTemplate<String, String> kafka;
        private final ObjectMapper mapper;
        private final String topic;

        public Kafka(KafkaTemplate<String, String> kafka, ObjectMapper mapper, String topic) {
            this.kafka = kafka;
            this.mapper = mapper;
            this.topic = topic;
        }

        @Override
        public void publish(ClaimEvent event) {
            send(event).whenComplete((result, ex) -> {
                if (ex != null) warn(event, ex);
            });
        }

        @Override
        public void publishAndWait(ClaimEvent event, Duration timeout) {
            try {
                send(event).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                warn(event, e);
            }
        }

        private CompletableFuture<?> send(ClaimEvent event) {
            try {
                String key = event.claimId() != null ? event.claimId().toString() : null;
                return kafka.send(topic, key, mapper.writeValueAsString(event));
            } catch (JsonProcessingException | RuntimeException e) { // RuntimeException: e.g. no metadata within max.block.ms
                return CompletableFuture.failedFuture(e);
            }
        }

        private static void warn(ClaimEvent event, Throwable e) {
            log.warn("Could not publish {} for claim {}: {}", event.eventType(), event.claimId(), e.getMessage());
        }
    }

    /** Used when no Kafka is configured (local runs, or the minimal AWS setup without MSK). */
    public static final class Logging implements EventPublisher {

        @Override
        public void publish(ClaimEvent event) {
            log.info("event {} claim={} status={}->{} tx={} phase={} participant={} change={}",
                    event.eventType(), event.claimId(), event.fromStatus(), event.toStatus(), event.txId(),
                    event.phase(), event.participant(), event.change());
        }
    }
}
