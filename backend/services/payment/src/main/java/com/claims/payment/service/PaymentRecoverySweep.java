package com.claims.payment.service;

import com.claims.participant.ParticipantTxState;
import com.claims.payment.domain.Payment;
import com.claims.payment.domain.PaymentStatus;
import com.claims.payment.repository.PaymentRepository;
import com.claims.payment.repository.PaymentTxLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes gateway work the protocol could not: a payment still AUTHORIZED whose settlement COMMITTED is
 * captured, one whose settlement ABORTED is voided. One try per payment per run (every 30 s).
 */
@Component
public class PaymentRecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoverySweep.class);

    private final PaymentRepository payments;
    private final PaymentTxLogRepository txLogs;
    private final PaymentParticipant participant;
    private final TransactionTemplate tx;

    public PaymentRecoverySweep(PaymentRepository payments, PaymentTxLogRepository txLogs, PaymentParticipant participant,
                                TransactionTemplate tx) {
        this.payments = payments;
        this.txLogs = txLogs;
        this.participant = participant;
        this.tx = tx;
    }

    @Scheduled(initialDelayString = "${claims.payment.recovery-interval}", fixedDelayString = "${claims.payment.recovery-interval}")
    public void run() {
        recover();
    }

    /** @return how many payments were finished */
    public int recover() {
        int done = 0;
        for (Payment p : payments.findAuthorizedWhereTxIs(ParticipantTxState.COMMITTED)) {
            done += finish(p, true);
        }
        for (Payment p : payments.findAuthorizedWhereTxIs(ParticipantTxState.ABORTED)) {
            done += finish(p, false);
        }
        return done;
    }

    private int finish(Payment stale, boolean capture) {
        try {
            Boolean finished = tx.execute(s -> {
                Payment p = payments.findById(stale.getId()).orElseThrow();
                if (p.getStatus() != PaymentStatus.AUTHORIZED) return false;
                var txId = txLogs.findFirstByPaymentId(p.getId()).map(t -> t.getTxId()).orElse(null);
                if (capture) participant.capture(p, txId, false);
                else participant.voidPayment(p, txId);
                return p.getStatus() != PaymentStatus.AUTHORIZED;
            });
            if (Boolean.TRUE.equals(finished)) {
                log.info("Recovery: payment {} {}", stale.getId(), capture ? "captured" : "voided");
                return 1;
            }
        } catch (RuntimeException e) {
            log.warn("Recovery of payment {} failed; next run will retry", stale.getId(), e);
        }
        return 0;
    }
}
