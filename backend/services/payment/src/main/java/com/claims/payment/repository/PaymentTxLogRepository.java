package com.claims.payment.repository;

import com.claims.participant.ParticipantTxState;
import com.claims.participant.ParticipantTxStore;
import com.claims.payment.domain.PaymentTxLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTxLogRepository extends JpaRepository<PaymentTxLog, UUID>, ParticipantTxStore<PaymentTxLog> {

    List<PaymentTxLog> findByStateAndUpdatedAtLessThanEqual(ParticipantTxState state, Instant before);

    Optional<PaymentTxLog> findFirstByPaymentId(UUID paymentId);

    @Override
    default Optional<PaymentTxLog> findTx(UUID txId) {
        return findById(txId);
    }

    @Override
    default PaymentTxLog saveTx(PaymentTxLog tx) {
        return saveAndFlush(tx);
    }

    @Override
    default List<PaymentTxLog> findStale(ParticipantTxState state, Instant before) {
        return findByStateAndUpdatedAtLessThanEqual(state, before);
    }
}
