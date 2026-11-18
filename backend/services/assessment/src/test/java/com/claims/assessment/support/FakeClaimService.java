package com.claims.assessment.support;

import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.GetClaimRequest;
import com.claims.proto.claim.v1.RecordAssessmentRequest;
import com.claims.proto.claim.v1.RecordReply;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Claim Intake's GetClaim (the claim's status) and RecordAssessment (recorded for assertions). */
public class FakeClaimService extends ClaimServiceGrpc.ClaimServiceImplBase {

    private final Map<String, ClaimStatus> status = new ConcurrentHashMap<>();
    private final List<RecordAssessmentRequest> recorded = new CopyOnWriteArrayList<>();
    private volatile Status recordFailure;

    public void status(UUID claimId, ClaimStatus s) {
        status.put(claimId.toString(), s);
    }

    /** Makes RecordAssessment fail with this status (null: succeed). */
    public void failRecordWith(Status s) {
        this.recordFailure = s;
    }

    public List<RecordAssessmentRequest> recordedFor(UUID claimId) {
        return recorded.stream().filter(r -> r.getClaimId().equals(claimId.toString())).toList();
    }

    @Override
    public void getClaim(GetClaimRequest req, StreamObserver<ClaimDetails> out) {
        ClaimStatus s = status.get(req.getClaimId());
        if (s == null) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        out.onNext(ClaimDetails.newBuilder().setClaimId(req.getClaimId()).setStatus(s).build());
        out.onCompleted();
    }

    @Override
    public void recordAssessment(RecordAssessmentRequest req, StreamObserver<RecordReply> out) {
        Status failure = recordFailure;
        if (failure != null) {
            out.onError(failure.asRuntimeException());
            return;
        }
        recorded.add(req);
        out.onNext(RecordReply.newBuilder().setStatus(status.getOrDefault(req.getClaimId(), ClaimStatus.UNDER_REVIEW)).build());
        out.onCompleted();
    }
}
