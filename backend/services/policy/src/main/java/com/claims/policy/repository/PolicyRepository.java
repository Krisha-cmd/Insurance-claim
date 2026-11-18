package com.claims.policy.repository;

import com.claims.policy.domain.Policy;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    @EntityGraph(attributePaths = {"policyholder", "plan"})
    Optional<Policy> findByPolicyNo(String policyNo);

    @EntityGraph(attributePaths = {"policyholder", "plan"})
    List<Policy> findByPolicyholderIdOrderByEndDateDesc(UUID policyholderId);
}
