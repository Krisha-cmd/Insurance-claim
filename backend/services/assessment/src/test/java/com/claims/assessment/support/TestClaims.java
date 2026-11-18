package com.claims.assessment.support;

import com.claims.proto.assessment.v1.AssessRequest;
import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimStatus;

import java.util.UUID;

/** ClaimDetails as Claim Intake sends them in Assess (treatment_category is the claim's treatment label). */
public final class TestClaims {

    /** patient1 as the login service issues them (sub and policyholderId). */
    public static final UUID PATIENT1_USER = UUID.fromString("4e33233c-a255-4c75-963a-6809cd3b0cdd");
    public static final UUID PATIENT1_HOLDER = UUID.fromString("a2b56321-c8b8-4159-93d6-3cf613b3c556");

    private TestClaims() {
    }

    public static ClaimDetails claim(UUID claimId, String policyNo, String category, String claimed) {
        return ClaimDetails.newBuilder()
                .setClaimId(claimId.toString()).setClaimNo("CLM-2026-000001").setPolicyNo(policyNo)
                .setPolicyholderId(PATIENT1_HOLDER.toString()).setSubmittedBy(PATIENT1_USER.toString())
                .setTreatmentCategory(category).setClaimedAmount(claimed).setCurrency("INR")
                .setStatus(ClaimStatus.DOCS_VERIFIED)
                .build();
    }

    public static AssessRequest assess(ClaimDetails claim) {
        return AssessRequest.newBuilder().setClaim(claim).build();
    }
}
