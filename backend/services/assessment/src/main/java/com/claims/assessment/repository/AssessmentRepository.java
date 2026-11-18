package com.claims.assessment.repository;

import com.claims.assessment.domain.Assessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AssessmentRepository extends JpaRepository<Assessment, UUID> {

    Optional<Assessment> findByClaimId(UUID claimId);

    long countByClaimId(UUID claimId);
}
