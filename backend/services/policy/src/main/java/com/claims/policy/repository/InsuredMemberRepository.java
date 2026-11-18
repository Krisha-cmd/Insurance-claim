package com.claims.policy.repository;

import com.claims.policy.domain.InsuredMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InsuredMemberRepository extends JpaRepository<InsuredMember, UUID> {

    List<InsuredMember> findByPolicyIdOrderByRelationAscFullNameAsc(UUID policyId);
}
