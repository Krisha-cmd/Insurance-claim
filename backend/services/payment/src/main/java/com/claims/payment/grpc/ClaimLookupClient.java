package com.claims.payment.grpc;

import com.claims.payment.config.PaymentProperties;
import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.GetClaimRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Claim Intake's ClaimService.GetClaim. CanCommit only carries the claim id and amount, so the participant
 * asks Claim Intake who filed the claim (the payee is found by that user).
 */
@Component
public class ClaimLookupClient {

    private final ClaimServiceGrpc.ClaimServiceBlockingStub stub;
    private final long timeoutMs;

    public ClaimLookupClient(ClaimServiceGrpc.ClaimServiceBlockingStub stub, PaymentProperties props) {
        this.stub = stub;
        this.timeoutMs = props.claimLookupTimeout().toMillis();
    }

    /** @return empty if the claim does not exist; throws StatusRuntimeException if Claim Intake cannot answer */
    public Optional<ClaimDetails> find(UUID claimId) {
        try {
            return Optional.of(stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .getClaim(GetClaimRequest.newBuilder().setClaimId(claimId.toString()).build()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) return Optional.empty();
            throw e;
        }
    }
}
