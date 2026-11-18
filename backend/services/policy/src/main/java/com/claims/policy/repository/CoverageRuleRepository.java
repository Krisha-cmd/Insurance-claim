package com.claims.policy.repository;

import com.claims.policy.domain.CoverageRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoverageRuleRepository extends JpaRepository<CoverageRule, UUID> {

    List<CoverageRule> findByPlanId(UUID planId);
}
