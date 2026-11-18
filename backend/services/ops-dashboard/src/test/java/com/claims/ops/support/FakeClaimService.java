package com.claims.ops.support;

import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.GetClaimRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/** Claim Intake's GetClaim: SeedItems.CLAIM was filed by SeedItems.OWNER; everything else is unknown. */
public class FakeClaimService extends ClaimServiceGrpc.ClaimServiceImplBase {

    @Override
    public void getClaim(GetClaimRequest req, StreamObserver<ClaimDetails> out) {
        if (!SeedItems.CLAIM.toString().equals(req.getClaimId())) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        out.onNext(ClaimDetails.newBuilder().setClaimId(req.getClaimId()).setSubmittedBy(SeedItems.OWNER.toString()).build());
        out.onCompleted();
    }
}
