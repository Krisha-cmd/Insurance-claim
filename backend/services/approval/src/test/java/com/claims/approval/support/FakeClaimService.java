package com.claims.approval.support;

import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.UpdateStatusReply;
import com.claims.proto.claim.v1.UpdateStatusRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Claim Intake's ClaimService: keeps a status per claim, records every request. */
public class FakeClaimService extends ClaimServiceGrpc.ClaimServiceImplBase {

    private final Map<String, ClaimStatus> statuses = new ConcurrentHashMap<>();
    private final List<UpdateStatusRequest> requests = new CopyOnWriteArrayList<>();
    private final Set<String> refuse = ConcurrentHashMap.newKeySet();

    public void claim(UUID claimId, ClaimStatus status) {
        statuses.put(claimId.toString(), status);
    }

    /** Answer FAILED_PRECONDITION for this claim, like Intake refusing a transition. */
    public void refuseUpdates(UUID claimId) {
        refuse.add(claimId.toString());
    }

    public ClaimStatus statusOf(UUID claimId) {
        return statuses.get(claimId.toString());
    }

    public List<UpdateStatusRequest> requestsFor(UUID claimId) {
        return requests.stream().filter(r -> r.getClaimId().equals(claimId.toString())).toList();
    }

    @Override
    public void updateStatus(UpdateStatusRequest req, StreamObserver<UpdateStatusReply> out) {
        requests.add(req);
        ClaimStatus previous = statuses.get(req.getClaimId());
        if (previous == null) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        if (refuse.contains(req.getClaimId())) {
            out.onError(Status.FAILED_PRECONDITION.withDescription("Claim is not under review.").asRuntimeException());
            return;
        }
        statuses.put(req.getClaimId(), req.getStatus());
        out.onNext(UpdateStatusReply.newBuilder().setPreviousStatus(previous).setStatus(req.getStatus())
                .setChanged(previous != req.getStatus()).build());
        out.onCompleted();
    }
}
