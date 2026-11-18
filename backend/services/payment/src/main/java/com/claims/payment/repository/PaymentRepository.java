package com.claims.payment.repository;

import com.claims.participant.ParticipantTxState;
import com.claims.payment.domain.Payment;
import com.claims.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    boolean existsByClaimIdAndStatusIn(UUID claimId, Collection<PaymentStatus> statuses);

    List<Payment> findByClaimIdOrderByCreatedAtAsc(UUID claimId);

    /** Payments still AUTHORIZED although their settlement already ended in {@code state} (the recovery sweep's work). */
    @Query("""
            select p from Payment p, PaymentTxLog t
            where t.paymentId = p.id and p.status = com.claims.payment.domain.PaymentStatus.AUTHORIZED and t.state = :state""")
    List<Payment> findAuthorizedWhereTxIs(@Param("state") ParticipantTxState state);
}
