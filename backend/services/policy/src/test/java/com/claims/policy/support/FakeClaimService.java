package com.claims.policy.support;

import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.GetClaimRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Claim Intake's GetClaim: answers the policy number registered for a claim. */
public class FakeClaimService extends ClaimServiceGrpc.ClaimServiceImplBase {

    private final Map<String, String> policyOfClaim = new ConcurrentHashMap<>();

    public UUID claimOn(String policyNo) {
        UUID claimId = UUID.randomUUID();
        policyOfClaim.put(claimId.toString(), policyNo);
        return claimId;
    }

    @Override
    public void getClaim(GetClaimRequest req, StreamObserver<ClaimDetails> out) {
        String policyNo = policyOfClaim.get(req.getClaimId());
        if (policyNo == null) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        out.onNext(ClaimDetails.newBuilder().setClaimId(req.getClaimId()).setPolicyNo(policyNo).build());
        out.onCompleted();
    }
}
