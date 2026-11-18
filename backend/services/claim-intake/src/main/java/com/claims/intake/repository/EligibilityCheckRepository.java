package com.claims.intake.repository;

import com.claims.intake.domain.EligibilityCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EligibilityCheckRepository extends JpaRepository<EligibilityCheck, UUID> {

    Optional<EligibilityCheck> findFirstByClaimIdOrderByCheckedAtDesc(UUID claimId);

    List<EligibilityCheck> findByClaimIdOrderByCheckedAtAsc(UUID claimId);
}
