package com.claims.intake.repository;

import com.claims.intake.domain.ClaimDocument;
import com.claims.intake.domain.DocType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClaimDocumentRepository extends JpaRepository<ClaimDocument, UUID> {

    List<ClaimDocument> findByClaimIdOrderByUploadedAtAsc(UUID claimId);

    List<ClaimDocument> findByClaimIdAndDocTypeAndSupersededByIsNull(UUID claimId, DocType docType);
}
