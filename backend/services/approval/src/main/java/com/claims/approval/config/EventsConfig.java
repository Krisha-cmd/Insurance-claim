package com.claims.approval.config;

import com.claims.approval.events.AuditPublisher;
import com.claims.approval.events.KafkaAuditPublisher;
import com.claims.approval.events.LoggingAuditPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.StringUtils;

@Configuration
public class EventsConfig {

    private static final Logger log = LoggerFactory.getLogger(EventsConfig.class);

    /** Kafka when KAFKA_BOOTSTRAP is set (MSK enabled), otherwise events are only logged. */
    @Bean
    AuditPublisher auditPublisher(@Value("${claims.events.kafka-bootstrap:}") String bootstrap,
                                  @Value("${claims.events.topic}") String topic,
                                  ObjectProvider<KafkaTemplate<String, String>> kafka,
                                  ObjectMapper mapper) {
        if (StringUtils.hasText(bootstrap)) {
            log.info("Publishing claim events to Kafka topic {} at {}", topic, bootstrap);
            return new KafkaAuditPublisher(kafka.getObject(), mapper, topic);
        }
        log.info("KAFKA_BOOTSTRAP is empty: claim events are written to the log only");
        return new LoggingAuditPublisher();
    }
}
