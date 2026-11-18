package com.claims.ops.config;

import com.claims.common.event.EventPublisher;
import com.claims.common.event.EventPublishers;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

@Configuration
public class AppConfig {

    /** Creates gRPC channels from targets. Tests replace it with in-process channels. */
    @FunctionalInterface
    public interface ChannelFactory {
        ManagedChannel create(String target);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventPublisher eventPublisher(@Value("${claims.events.kafka-bootstrap:}") String bootstrap,
                                  @Value("${claims.events.topic}") String topic,
                                  ObjectProvider<KafkaTemplate<String, String>> kafka, ObjectMapper mapper) {
        return EventPublishers.create(bootstrap, topic, kafka, mapper);
    }

    @Bean
    ChannelFactory grpcChannelFactory() {
        return target -> Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
    }

    @Bean(destroyMethod = "shutdown")
    ManagedChannel claimChannel(ChannelFactory f, @Value("${claims.grpc.claim-target}") String target) {
        return f.create(target);
    }

    @Bean
    ClaimServiceGrpc.ClaimServiceBlockingStub claimStub(@Qualifier("claimChannel") ManagedChannel ch) {
        return ClaimServiceGrpc.newBlockingStub(ch);
    }
}
