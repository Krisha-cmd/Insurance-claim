package com.claims.payment.service;

import com.claims.participant.ThreePhaseParticipant;
import com.claims.participant.contract.ThreePhaseParticipantContract;
import com.claims.payment.domain.Payment;
import com.claims.payment.domain.PaymentStatus;
import com.claims.payment.domain.PaymentTxLog;
import com.claims.payment.repository.PaymentRepository;
import com.claims.payment.support.GatewayStubs;
import com.claims.payment.support.PaymentTest;
import com.claims.payment.support.PaymentTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared 3PC participant cases (participant test-jar) against PaymentParticipant + Postgres + WireMock. */
@PaymentTest
class PaymentParticipantContractTest extends ThreePhaseParticipantContract<PaymentTxLog> {

    @Autowired PaymentParticipant participant;
    @Autowired PaymentRepository payments;

    @BeforeEach
    void healthyGateway() {
        GatewayStubs.gateway().healthy();
    }

    @Override protected ThreePhaseParticipant<PaymentTxLog> participant() { return participant; }
    @Override protected void advanceClock(Duration d) { PaymentTestConfig.CLOCK.advance(d); }
    @Override protected Duration prepareTimeout() { return Duration.ofMinutes(5); }
    @Override protected Duration precommitTimeout() { return Duration.ofMinutes(2); }

    /** Filed by patient1, who has a verified payee account. */
    @Override protected UUID committableClaim() { return PaymentTestConfig.CLAIMS.claimBy(PaymentTestConfig.PATIENT1); }

    /** Filed by a user with no payee account. */
    @Override protected UUID uncommittableClaim() { return PaymentTestConfig.CLAIMS.claimBy(UUID.randomUUID()); }

    private List<Payment> paymentsOf(UUID claimId) { return payments.findByClaimIdOrderByCreatedAtAsc(claimId); }

    @Override protected void assertPrepared(UUID c) {
        assertThat(paymentsOf(c)).singleElement().satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED));
    }

    @Override protected void assertCommitted(UUID c) {
        assertThat(paymentsOf(c)).singleElement().satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(GatewayStubs.gateway().calls("/capture", "authId", p.getAuthId())).as("captured exactly once").isEqualTo(1);
        });
    }

    @Override protected void assertReleased(UUID c) {
        assertThat(paymentsOf(c)).singleElement().satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.VOIDED));
    }

    @Override protected void assertUntouched(UUID c) {
        assertThat(paymentsOf(c)).isEmpty();
    }
}
