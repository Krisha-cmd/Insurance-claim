package com.claims.payment.config;

import com.claims.common.event.EventPublisher;
import com.claims.common.event.EventPublishers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Kafka when KAFKA_BOOTSTRAP is set (MSK enabled), otherwise events are only logged. */
    @Bean
    EventPublisher eventPublisher(@Value("${claims.events.kafka-bootstrap:}") String bootstrap,
                                  @Value("${claims.events.topic}") String topic,
                                  ObjectProvider<KafkaTemplate<String, String>> kafka, ObjectMapper mapper) {
        return EventPublishers.create(bootstrap, topic, kafka, mapper);
    }
}
