package com.claims.intake.repository;

import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID>, JpaSpecificationExecutor<Claim> {

    List<Claim> findBySubmittedByOrderByCreatedAtDesc(UUID submittedBy);

    Page<Claim> findByStatusInOrderByCreatedAtAsc(Collection<ClaimStatus> statuses, Pageable page);

    List<Claim> findByStatusAndUpdatedAtLessThanEqual(ClaimStatus status, Instant before);
}
