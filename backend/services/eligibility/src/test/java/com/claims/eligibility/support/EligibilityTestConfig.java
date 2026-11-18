package com.claims.eligibility.support;

import com.claims.common.event.EventPublisher;
import com.claims.eligibility.config.GrpcClientConfig;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

@TestConfiguration(proxyBeanMethods = false)
public class EligibilityTestConfig {

    public static final FakePolicyService POLICY = new FakePolicyService();
    public static final FakeClaimService CLAIMS = new FakeClaimService();
    public static final RecordingEventPublisher EVENTS = new RecordingEventPublisher();

    static {
        try {
            InProcessServerBuilder.forName("policy").addService(POLICY).build().start();
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
    EventPublisher recordingEventPublisher() {
        return EVENTS;
    }
}
