package com.claims.payment.repository;

import com.claims.payment.domain.GatewayAction;
import com.claims.payment.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    List<PaymentAttempt> findByPaymentIdOrderByIdAsc(UUID paymentId);

    long countByPaymentIdAndAction(UUID paymentId, GatewayAction action);
}
