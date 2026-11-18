package com.claims.intake.support;

import com.claims.common.event.EventPublisher;
import com.claims.intake.config.GrpcClientConfig;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

/** Fakes for everything outside Claim Intake, shared by every @IntakeTest class. */
@TestConfiguration(proxyBeanMethods = false)
@ImportTestcontainers(IntakeContainers.class)
public class IntakeTestConfig {

    public static final FakePolicy POLICY = new FakePolicy();
    public static final FakeChecks.Eligibility ELIGIBILITY = new FakeChecks.Eligibility();
    public static final FakeChecks.Assessment ASSESSMENT = new FakeChecks.Assessment();
    public static final TickingClock CLOCK = new TickingClock();

    static {
        try {
            InProcessServerBuilder.forName("policy").addService(POLICY).build().start();
            InProcessServerBuilder.forName("eligibility").addService(ELIGIBILITY).build().start();
            InProcessServerBuilder.forName("assessment").addService(ASSESSMENT).build().start();
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
