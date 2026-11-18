package com.claims.eligibility.grpc;

import com.claims.common.error.ApiException;
import com.claims.eligibility.config.EligibilityProperties;
import com.claims.eligibility.rules.RuleResult;
import com.claims.proto.claim.v1.Actor;
import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.GetClaimRequest;
import com.claims.proto.claim.v1.RecordEligibilityRequest;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Claim Intake's ClaimService: the claim with its latest eligibility (GetClaim), and RecordEligibility. */
@Component
public class ClaimClient {

    private final ClaimServiceGrpc.ClaimServiceBlockingStub stub;
    private final long timeoutMs;

    public ClaimClient(ClaimServiceGrpc.ClaimServiceBlockingStub claimStub, EligibilityProperties props) {
        this.stub = claimStub;
        this.timeoutMs = props.callTimeout().toMillis();
    }

    public ClaimDetails getClaim(UUID claimId) {
        try {
            return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .getClaim(GetClaimRequest.newBuilder().setClaimId(claimId.toString()).build());
        } catch (StatusRuntimeException e) {
            throw translate(e, claimId);
        }
    }

    /** Records the result in Claim Intake: an override sets {@code overridden} with the adjuster's reason and comment. */
    public void recordEligibility(UUID claimId, boolean eligible, List<RuleResult> rules, boolean overridden,
                                  String reason, String comment, Actor actor) {
        RecordEligibilityRequest.Builder req = RecordEligibilityRequest.newBuilder()
                .setClaimId(claimId.toString())
                .setEligible(eligible)
                .setOverridden(overridden)
                .setOverrideReason(reason == null ? "" : reason)
                .setOverrideComment(comment == null ? "" : comment)
                .setActor(actor);
        rules.forEach(r -> req.addRules(com.claims.proto.claim.v1.RuleResult.newBuilder()
                .setRule(r.rule()).setLabel(r.label()).setPassed(r.passed()).setDetail(r.message()).build()));
        try {
            stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).recordEligibility(req.build());
        } catch (StatusRuntimeException e) {
            throw translate(e, claimId);
        }
    }

    private static ApiException translate(StatusRuntimeException e, UUID claimId) {
        String detail = e.getStatus().getDescription();
        return switch (e.getStatus().getCode()) {
            case NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "Claim " + claimId + " was not found.");
            case INVALID_ARGUMENT -> new ApiException(HttpStatus.BAD_REQUEST,
                    detail != null ? detail : "The claim service refused the request.");
            case FAILED_PRECONDITION -> new ApiException(HttpStatus.CONFLICT,
                    detail != null ? detail : "The claim cannot be changed now.");
            default -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The claim service is not available. Please try again shortly.");
        };
    }
}
