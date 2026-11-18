package com.claims.approval.repository;

import com.claims.approval.domain.ApprovalDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> {

    Optional<ApprovalDecision> findByClaimId(UUID claimId);
}
