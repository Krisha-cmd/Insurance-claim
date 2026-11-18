package com.claims.policy.repository;

import com.claims.policy.domain.PolicyBalance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PolicyBalanceRepository extends JpaRepository<PolicyBalance, UUID> {
}
