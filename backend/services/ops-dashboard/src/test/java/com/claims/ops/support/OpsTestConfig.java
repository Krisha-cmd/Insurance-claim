package com.claims.ops.support;

import com.claims.common.event.EventPublisher;
import com.claims.ops.config.AppConfig;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

@TestConfiguration(proxyBeanMethods = false)
@ImportTestcontainers(OpsContainers.class)
public class OpsTestConfig {

    static {
        try {
            InProcessServerBuilder.forName("claim-intake").addService(new FakeClaimService()).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    @Primary
    AppConfig.ChannelFactory inProcessChannels() {
        return target -> InProcessChannelBuilder.forName(target).build();
    }

    @Bean
    @Primary
    EventPublisher recordingEventPublisher() {
        return new RecordingEventPublisher();
    }
}
