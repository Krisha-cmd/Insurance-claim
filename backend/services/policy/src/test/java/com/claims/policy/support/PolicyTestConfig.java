package com.claims.policy.support;

import com.claims.common.event.EventPublisher;
import com.claims.policy.config.GrpcClientConfig;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

@TestConfiguration(proxyBeanMethods = false)
@ImportTestcontainers(PolicyContainers.class)
public class PolicyTestConfig {

    public static final FakeClaimService CLAIMS = new FakeClaimService();
    public static final TickingClock CLOCK = new TickingClock();

    static {
        try {
            InProcessServerBuilder.forName("claim-intake").addService(CLAIMS).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    @Primary
    GrpcClientConfig.ChannelFactory inProcessChannels() {
        return target -> InProcessChannelBuilder.forName(target).build();
    }

    @Bean
    @Primary
    TickingClock testClock() {
        return CLOCK;
    }

    @Bean
    @Primary
    EventPublisher recordingEventPublisher() {
        return new RecordingEventPublisher();
    }
}
