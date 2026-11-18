package com.claims.payment.service;

import com.claims.participant.ParticipantTxState;
import com.claims.participant.VoteResult;
import com.claims.payment.domain.GatewayAction;
import com.claims.payment.domain.Payment;
import com.claims.payment.domain.PaymentAttempt;
import com.claims.payment.domain.PaymentStatus;
import com.claims.payment.repository.PaymentAttemptRepository;
import com.claims.payment.repository.PaymentRepository;
import com.claims.payment.repository.PaymentTxLogRepository;
import com.claims.payment.support.GatewayStubs;
import com.claims.payment.support.PaymentTest;
import com.claims.payment.support.PaymentTestConfig;
import com.claims.payment.support.RecordingEventPublisher;
import com.claims.common.event.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The acceptance cases, with WireMock as the gateway. */
@PaymentTest
class PaymentParticipantIntegrationTest {

    @Autowired PaymentParticipant participant;
    @Autowired PaymentRecoverySweep sweep;
    @Autowired PaymentRepository payments;
    @Autowired PaymentAttemptRepository attempts;
    @Autowired PaymentTxLogRepository txLogs;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventPublisher events;

    private final GatewayStubs gateway = GatewayStubs.gateway();
    private static final BigDecimal AMOUNT = new BigDecimal("67500.00");

    @BeforeEach
    void healthyGateway() {
        gateway.healthy();
    }

    private UUID claim() {
        return PaymentTestConfig.CLAIMS.claimBy(PaymentTestConfig.PATIENT1);
    }

    private Payment paymentOf(UUID txId) {
        return payments.findById(txLogs.findById(txId).orElseThrow().getPaymentId()).orElseThrow();
    }

    private List<GatewayAction> actions(Payment p) {
        return attempts.findByPaymentIdOrderByIdAsc(p.getId()).stream().map(PaymentAttempt::getAction).toList();
    }

    @Test
    void yesPath_authorizeThenCapture() {
        UUID claimId = claim(), txId = UUID.randomUUID();

        VoteResult vote = participant.canCommit(txId, claimId, AMOUNT, 1);
        assertThat(vote.yes()).isTrue();
        Payment authorized = paymentOf(txId);
        assertThat(authorized.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(authorized.getAuthId()).isEqualTo("AUTH-" + authorized.getId());
        assertThat(authorized.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(gateway.calls("/authorize", "paymentId", authorized.getId().toString())).isEqualTo(1);

        participant.preCommit(txId);
        assertThat(gateway.calls("/capture")).as("PreCommit does nothing external").isZero();
        assertThat(participant.doCommit(txId)).isEqualTo(ParticipantTxState.COMMITTED);

        Payment paid = paymentOf(txId);
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(paid.getGatewayRef()).isEqualTo("UTR-AUTH-" + paid.getId());
        assertThat(actions(paid)).containsExactly(GatewayAction.AUTHORIZE, GatewayAction.CAPTURE);
        assertThat(((RecordingEventPublisher) events).typesFor(claimId)).contains("PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED", "TX_VOTE");

        participant.doCommit(txId);
        assertThat(gateway.calls("/capture", "authId", paid.getAuthId())).as("a repeated DoCommit does not capture again").isEqualTo(1);
    }

    @Test
    void failAuthorize_votesNo_andThePaymentIsFailed() {
        gateway.failAuthorize();
        UUID txId = UUID.randomUUID();

        VoteResult vote = participant.canCommit(txId, claim(), AMOUNT, 1);

        assertThat(vote.yes()).isFalse();
        assertThat(vote.reason()).isEqualTo("Payment gateway: Gateway unavailable (stub) (HTTP_503)");
        Payment p = paymentOf(txId);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(attempts.findByPaymentIdOrderByIdAsc(p.getId())).singleElement().satisfies(a -> {
            assertThat(a.getAction()).isEqualTo(GatewayAction.AUTHORIZE);
            assertThat(a.getResultCode()).isEqualTo("HTTP_503");
        });
        participant.abort(txId);
        assertThat(gateway.calls("/void")).as("nothing to void").isZero();
    }

    @Test
    void aSlowGateway_timesOut_andVotesNo() {
        gateway.slowAuthorize(2000); // read timeout is 1 s in tests (3 s in production)
        UUID txId = UUID.randomUUID();

        VoteResult vote = participant.canCommit(txId, claim(), AMOUNT, 1);

        assertThat(vote.yes()).isFalse();
        assertThat(vote.reason()).isEqualTo("Payment gateway: The payment gateway did not answer in time (TIMEOUT)");
        assertThat(attempts.findByPaymentIdOrderByIdAsc(paymentOf(txId).getId()).getFirst().getResultCode()).isEqualTo("TIMEOUT");
    }

    @Test
    void abortAfterYes_voidsTheAuthorization() {
        UUID txId = UUID.randomUUID();
        assertThat(participant.canCommit(txId, claim(), AMOUNT, 1).yes()).isTrue();

        participant.abort(txId);

        Payment p = paymentOf(txId);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.VOIDED);
        assertThat(gateway.calls("/void", "authId", p.getAuthId())).isEqualTo(1);
        assertThat(actions(p)).containsExactly(GatewayAction.AUTHORIZE, GatewayAction.VOID);
    }

    @Test
    void captureFailsTwiceThenSucceeds_isCaptured() {
        gateway.failCaptureTimes(2);
        UUID txId = UUID.randomUUID();
        participant.canCommit(txId, claim(), AMOUNT, 1);
        participant.preCommit(txId);

        participant.doCommit(txId);

        Payment p = paymentOf(txId);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        List<PaymentAttempt> captures = attempts.findByPaymentIdOrderByIdAsc(p.getId()).stream()
                .filter(a -> a.getAction() == GatewayAction.CAPTURE).toList();
        assertThat(captures).extracting(PaymentAttempt::getResultCode).containsExactly("HTTP_503", "HTTP_503", "OK");
    }

    @Test
    void captureThatNeverGetsThrough_staysCommitted_andTheSweepCapturesLater() {
        gateway.failCaptureAlways();
        UUID claimId = claim(), txId = UUID.randomUUID();
        participant.canCommit(txId, claimId, AMOUNT, 1);

        assertThat(participant.doCommit(txId)).as("the decision is commit; the coordinator gets its ack")
                .isEqualTo(ParticipantTxState.COMMITTED);
        assertThat(paymentOf(txId).getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(((RecordingEventPublisher) events).typesFor(claimId)).contains("PAYMENT_CAPTURE_PENDING");

        assertThat(sweep.recover()).as("gateway still down").isZero();
        gateway.healthy();
        assertThat(sweep.recover()).isGreaterThanOrEqualTo(1);
        assertThat(paymentOf(txId).getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(actions(paymentOf(txId)).stream().filter(a -> a == GatewayAction.CAPTURE)).hasSize(5); // 3 + 1 + 1
    }

    @Test
    void aClaimIsNeverPaidTwice_butCanBeRetriedAfterAnAbort() {
        UUID claimId = claim();
        UUID first = UUID.randomUUID();
        participant.canCommit(first, claimId, AMOUNT, 1);
        assertThat(participant.canCommit(UUID.randomUUID(), claimId, AMOUNT, 2).reason())
                .isEqualTo("Another payment for claim " + claimId + " is in progress");

        participant.abort(first);
        UUID retry = UUID.randomUUID();
        assertThat(participant.canCommit(retry, claimId, AMOUNT, 2).yes()).as("retry after the abort").isTrue();
        participant.doCommit(retry);
        assertThat(participant.canCommit(UUID.randomUUID(), claimId, AMOUNT, 3).reason())
                .isEqualTo("Claim " + claimId + " has already been paid");
        assertThat(payments.findByClaimIdOrderByCreatedAtAsc(claimId)).extracting(Payment::getStatus)
                .containsExactly(PaymentStatus.VOIDED, PaymentStatus.CAPTURED);
    }

    @Test
    void noPayeeOrAnUnverifiedPayee_votesNo_withoutCallingTheGateway() {
        UUID nobody = UUID.randomUUID();
        assertThat(participant.canCommit(UUID.randomUUID(), PaymentTestConfig.CLAIMS.claimBy(nobody), AMOUNT, 1).reason())
                .isEqualTo("No payee account on file for the policyholder");

        UUID unverified = UUID.randomUUID();
        jdbc.update("insert into payee_account (id, user_id, account_holder, account_no_masked, ifsc, verified) "
                + "values (?, ?, 'X', 'XXXXXX0000', 'SBIN0000001', false)", UUID.randomUUID(), unverified);
        assertThat(participant.canCommit(UUID.randomUUID(), PaymentTestConfig.CLAIMS.claimBy(unverified), AMOUNT, 1).reason())
                .isEqualTo("The payee account XXXXXX0000 is not verified");

        assertThat(participant.canCommit(UUID.randomUUID(), UUID.randomUUID(), AMOUNT, 1).reason()).endsWith("not found");
        assertThat(gateway.calls("/authorize")).isZero();
    }

    @Test
    void theSweepVoidsAnAbortedPaymentWhoseVoidFailed() {
        UUID txId = UUID.randomUUID();
        participant.canCommit(txId, claim(), AMOUNT, 1);
        gateway.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/void")
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.serverError()));

        participant.abort(txId);
        assertThat(paymentOf(txId).getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        gateway.healthy();
        sweep.recover();
        assertThat(paymentOf(txId).getStatus()).isEqualTo(PaymentStatus.VOIDED);
    }
}
