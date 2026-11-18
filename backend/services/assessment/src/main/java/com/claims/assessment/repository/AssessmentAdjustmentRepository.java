package com.claims.assessment.repository;

import com.claims.assessment.domain.AssessmentAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/** Adjustments are saved through their Assessment (cascade); this is for reading the history directly. */
public interface AssessmentAdjustmentRepository extends JpaRepository<AssessmentAdjustment, UUID> {

    @Query("select a from AssessmentAdjustment a where a.assessment.claimId = :claimId order by a.adjustedAt")
    List<AssessmentAdjustment> findByClaimId(UUID claimId);
}
