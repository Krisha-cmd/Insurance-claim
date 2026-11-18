package com.claims.payment.support;

import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.GetClaimRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Claim Intake's GetClaim: answers who filed each registered claim. */
public class FakeClaimService extends ClaimServiceGrpc.ClaimServiceImplBase {

    private final Map<String, String> claimant = new ConcurrentHashMap<>();

    public UUID claimBy(UUID userId) {
        UUID claimId = UUID.randomUUID();
        claimant.put(claimId.toString(), userId.toString());
        return claimId;
    }

    @Override
    public void getClaim(GetClaimRequest req, StreamObserver<ClaimDetails> out) {
        String user = claimant.get(req.getClaimId());
        if (user == null) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        out.onNext(ClaimDetails.newBuilder().setClaimId(req.getClaimId()).setSubmittedBy(user).build());
        out.onCompleted();
    }
}
