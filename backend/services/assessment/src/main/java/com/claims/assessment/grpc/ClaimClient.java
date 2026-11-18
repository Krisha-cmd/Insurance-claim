package com.claims.assessment.grpc;

import com.claims.assessment.config.AssessmentProperties;
import com.claims.common.error.ApiException;
import com.claims.proto.claim.v1.Actor;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.GetClaimRequest;
import com.claims.proto.claim.v1.RecordAssessmentRequest;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Claim Intake's ClaimService: the claim's status (GetClaim) and RecordAssessment after an adjustment. */
@Component
public class ClaimClient {

    private final ClaimServiceGrpc.ClaimServiceBlockingStub stub;
    private final long timeoutMs;

    public ClaimClient(ClaimServiceGrpc.ClaimServiceBlockingStub claimStub, AssessmentProperties props) {
        this.stub = claimStub;
        this.timeoutMs = props.callTimeout().toMillis();
    }

    public ClaimStatus status(UUID claimId) {
        try {
            return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .getClaim(GetClaimRequest.newBuilder().setClaimId(claimId.toString()).build()).getStatus();
        } catch (StatusRuntimeException e) {
            throw translate(e, claimId);
        }
    }

    /** Tells Claim Intake the new proposed amount, so the claim shows it. Does not change the claim's status. */
    public void recordAssessment(UUID claimId, BigDecimal amount, String comment, Actor actor) {
        try {
            stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .recordAssessment(RecordAssessmentRequest.newBuilder()
                            .setClaimId(claimId.toString())
                            .setProposedAmount(amount.toPlainString())
                            .setComment(comment)
                            .setActor(actor)
                            .build());
        } catch (StatusRuntimeException e) {
            throw translate(e, claimId);
        }
    }

    private static ApiException translate(StatusRuntimeException e, UUID claimId) {
        String detail = e.getStatus().getDescription();
        return switch (e.getStatus().getCode()) {
            case NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "Claim " + claimId + " was not found.");
            case INVALID_ARGUMENT -> new ApiException(HttpStatus.BAD_REQUEST,
                    detail != null ? detail : "The claim service refused the amount.");
            case FAILED_PRECONDITION -> new ApiException(HttpStatus.CONFLICT,
                    detail != null ? detail : "The claim cannot be changed now.");
            default -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The claim service is not available. Please try again shortly.");
        };
    }
}
