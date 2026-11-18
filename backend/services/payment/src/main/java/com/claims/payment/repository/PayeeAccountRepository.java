package com.claims.payment.repository;

import com.claims.payment.domain.PayeeAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayeeAccountRepository extends JpaRepository<PayeeAccount, UUID> {

    /** Verified accounts first. */
    List<PayeeAccount> findByUserIdOrderByVerifiedDesc(UUID userId);
}
