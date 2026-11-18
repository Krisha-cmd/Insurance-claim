package com.claims.approval.support;

import com.claims.approval.api.CurrentUser;
import com.claims.approval.domain.CoordinatorState;
import com.claims.approval.domain.SettlementTx;
import com.claims.common.event.EventPublisher;
import com.claims.approval.grpc.GrpcChannelFactory;
import com.claims.approval.repository.SettlementTxRepository;
import com.claims.approval.service.ChaosMode;
import com.claims.approval.service.ChaosSwitch;
import com.claims.approval.service.Halter;
import com.claims.proto.claim.v1.ClaimStatus;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * Real Spring context + real Postgres (Testcontainers) + in-process gRPC servers standing in for
 * Claim Intake, Policy, Payment (the three participants), Assessment and Claim Intake's ClaimService.
 * The context is cached and shared by every subclass.
 */
@SpringBootTest
@Import(IntegrationTestBase.FakesConfig.class)
@TestPropertySource(properties = {
        "claims.coordinator.participants[0].name=claim-intake",
        "claims.coordinator.participants[0].target=claim-intake",
        "claims.coordinator.participants[1].name=policy",
        "claims.coordinator.participants[1].target=policy",
        "claims.coordinator.participants[2].name=payment",
        "claims.coordinator.participants[2].target=payment",
        "claims.approval.assessment-target=assessment",
        "claims.approval.claim-target=claim-service",
        "claims.coordinator.phase-timeout=500ms",
        "claims.coordinator.recovery-stale-after=0s",
        "claims.coordinator.recovery-interval=PT1H", // tests trigger recovery themselves
        "claims.events.kafka-bootstrap=",
})
public abstract class IntegrationTestBase {

    // One container for the whole test run (shared by the cached context).
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    public static final FakeParticipant INTAKE = new FakeParticipant("claim-intake");
    public static final FakeParticipant POLICY = new FakeParticipant("policy");
    public static final FakeParticipant PAYMENT = new FakeParticipant("payment");
    public static final List<FakeParticipant> PARTICIPANTS = List.of(INTAKE, POLICY, PAYMENT);
    public static final FakeAssessment ASSESSMENT = new FakeAssessment();
    public static final FakeClaimService CLAIMS = new FakeClaimService();

    static {
        POSTGRES.start();
        try {
            for (FakeParticipant p : PARTICIPANTS) start(p.name(), p);
            start("assessment", ASSESSMENT);
            start("claim-service", CLAIMS);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server start(String name, io.grpc.BindableService service) throws IOException {
        return InProcessServerBuilder.forName(name).addService(service).build().start();
    }

    public static final CurrentUser ADJUSTER =
            new CurrentUser(UUID.fromString("997bacd0-5b29-4554-b398-52e9f89bfa6c"), "ADJUSTER", "Rohan Mehta");

    @TestConfiguration
    static class FakesConfig {

        @Bean
        @Primary
        GrpcChannelFactory inProcessChannels() {
            return target -> InProcessChannelBuilder.forName(target).build();
        }

        @Bean
        @Primary
        Halter throwingHalter() {
            return reason -> {
                throw new SimulatedCrash(reason);
            };
        }

        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }
    }

    @Autowired protected SettlementTxRepository txs;
    @Autowired protected ChaosSwitch chaos;
    @Autowired protected EventPublisher auditPublisher;

    protected RecordingAuditPublisher events() {
        return (RecordingAuditPublisher) auditPublisher;
    }

    @BeforeEach
    @AfterEach
    void resetFakes() {
        PARTICIPANTS.forEach(FakeParticipant::reset);
        chaos.set(ChaosMode.NONE);
    }

    /** A claim under review with an assessed amount. */
    protected UUID claimUnderReview(String amount) {
        UUID claimId = UUID.randomUUID();
        CLAIMS.claim(claimId, ClaimStatus.UNDER_REVIEW);
        ASSESSMENT.assessed(claimId, amount);
        return claimId;
    }

    protected SettlementTx awaitState(UUID txId, CoordinatorState state) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .until(() -> txs.findById(txId).map(SettlementTx::getState).orElse(null) == state);
        return txs.findById(txId).orElseThrow();
    }
}
