package com.claims.eligibility.support;

import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.EligibilityRecord;
import com.claims.proto.claim.v1.GetClaimRequest;
import com.claims.proto.claim.v1.RecordEligibilityRequest;
import com.claims.proto.claim.v1.RecordReply;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Claim Intake's GetClaim and RecordEligibility. Like the real one, a recorded result becomes the claim's
 * {@code latest_eligibility} on the next GetClaim (checked_by "ROLE:Name").
 */
public class FakeClaimService extends ClaimServiceGrpc.ClaimServiceImplBase {

    public static final UUID PATIENT1_USER = UUID.fromString("4e33233c-a255-4c75-963a-6809cd3b0cdd");
    public static final UUID PATIENT1_HOLDER = UUID.fromString("a2b56321-c8b8-4159-93d6-3cf613b3c556");

    private final Map<String, ClaimDetails> claims = new ConcurrentHashMap<>();
    private final List<RecordEligibilityRequest> recorded = new CopyOnWriteArrayList<>();
    private volatile Status recordFailure;

    /** A claim of patient1's, as Claim Intake would describe it. {@code checked}: already has an automatic result. */
    public UUID claim(String policyNo, String memberId, String admitted, String category, ClaimStatus status, Boolean checked) {
        UUID id = UUID.randomUUID();
        ClaimDetails.Builder c = ClaimDetails.newBuilder()
                .setClaimId(id.toString()).setClaimNo("CLM-2026-" + id.toString().substring(0, 6)).setPolicyNo(policyNo)
                .setMemberId(memberId).setPolicyholderId(PATIENT1_HOLDER.toString()).setSubmittedBy(PATIENT1_USER.toString())
                .setHospitalName("City Hospital").setAdmissionDate(admitted).setDischargeDate(admitted)
                .setDiagnosis("Appendicitis").setTreatmentCategory(category).setClaimedAmount("67500.00")
                .setStatus(status).setCurrency("INR");
        if (checked != null) {
            c.setLatestEligibility(EligibilityRecord.newBuilder().setEligible(checked).setOverridden(false)
                    .setCheckedBy("SYSTEM:claim-intake").setCheckedAt(Instant.now().toString()));
        }
        claims.put(id.toString(), c.build());
        return id;
    }

    public void status(UUID claimId, ClaimStatus status) {
        claims.computeIfPresent(claimId.toString(), (k, c) -> c.toBuilder().setStatus(status).build());
    }

    public void failRecordWith(Status s) {
        this.recordFailure = s;
    }

    public List<RecordEligibilityRequest> recordedFor(UUID claimId) {
        return recorded.stream().filter(r -> r.getClaimId().equals(claimId.toString())).toList();
    }

    @Override
    public void getClaim(GetClaimRequest req, StreamObserver<ClaimDetails> out) {
        ClaimDetails c = claims.get(req.getClaimId());
        if (c == null) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        out.onNext(c);
        out.onCompleted();
    }

    @Override
    public void recordEligibility(RecordEligibilityRequest req, StreamObserver<RecordReply> out) {
        Status failure = recordFailure;
        if (failure != null) {
            out.onError(failure.asRuntimeException());
            return;
        }
        ClaimDetails c = claims.get(req.getClaimId());
        if (c == null) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        recorded.add(req);
        claims.put(req.getClaimId(), c.toBuilder().setLatestEligibility(EligibilityRecord.newBuilder()
                .setEligible(req.getEligible()).setOverridden(req.getOverridden())
                .setOverrideReason(req.getOverrideReason()).setOverrideComment(req.getOverrideComment())
                .setCheckedBy(req.getActor().getRole() + ":" + req.getActor().getName())
                .setCheckedAt(Instant.now().toString())).build());
        out.onNext(RecordReply.newBuilder().setStatus(c.getStatus()).build());
        out.onCompleted();
    }
}
