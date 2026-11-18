package com.claims.ops.client;

import com.claims.common.error.ApiException;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.GetClaimRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Who filed a claim (Claim Intake's ClaimService.GetClaim), so a policyholder may read only their own audit trail. */
@Component
public class ClaimOwnerClient {

    private final ClaimServiceGrpc.ClaimServiceBlockingStub stub;

    public ClaimOwnerClient(ClaimServiceGrpc.ClaimServiceBlockingStub stub) {
        this.stub = stub;
    }

    /** @return the user id that filed the claim; empty if the claim does not exist */
    public Optional<UUID> submittedBy(UUID claimId) {
        try {
            String by = stub.withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getClaim(GetClaimRequest.newBuilder().setClaimId(claimId.toString()).build()).getSubmittedBy();
            return by.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(by));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) return Optional.empty();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The claim service is not available. Please try again shortly.");
        }
    }
}
