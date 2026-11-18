package com.claims.intake.grpc;

import com.claims.common.error.ApiException;
import com.claims.intake.domain.Actor;
import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.mapper.ClaimMapper;
import com.claims.intake.service.ClaimService;
import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.GetClaimRequest;
import com.claims.proto.claim.v1.RecordAssessmentRequest;
import com.claims.proto.claim.v1.RecordEligibilityRequest;
import com.claims.proto.claim.v1.RecordReply;
import com.claims.proto.claim.v1.UpdateStatusReply;
import com.claims.proto.claim.v1.UpdateStatusRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ClaimService (claims/claim/v1/claim.proto) for Approval, Eligibility and Assessment.
 * Errors: unknown claim → NOT_FOUND, disallowed transition → FAILED_PRECONDITION (Approval turns it into 409),
 * bad input → INVALID_ARGUMENT.
 */
@Component
public class ClaimGrpcService extends ClaimServiceGrpc.ClaimServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ClaimGrpcService.class);

    private final ClaimService claims;
    private final ClaimMapper mapper;

    public ClaimGrpcService(ClaimService claims, ClaimMapper mapper) {
        this.claims = claims;
        this.mapper = mapper;
    }

    @Override
    public void updateStatus(UpdateStatusRequest req, StreamObserver<UpdateStatusReply> out) {
        respond(out, () -> {
            ClaimStatus to = toDomain(req.getStatus());
            ClaimService.StatusChange change = claims.updateStatus(uuid(req.getClaimId()), to, req.getReason(), actor(req.getActor()));
            return UpdateStatusReply.newBuilder()
                    .setPreviousStatus(toProto(change.previous()))
                    .setStatus(toProto(change.current()))
                    .setChanged(change.changed())
                    .build();
        });
    }

    @Override
    public void getClaim(GetClaimRequest req, StreamObserver<ClaimDetails> out) {
        respond(out, () -> mapper.details(claims.require(uuid(req.getClaimId()))));
    }

    @Override
    public void recordEligibility(RecordEligibilityRequest req, StreamObserver<RecordReply> out) {
        respond(out, () -> reply(claims.recordEligibility(uuid(req.getClaimId()), req.getEligible(),
                mapper.rulesJson(req.getRulesList()), req.getOverridden(), req.getOverrideComment(), actor(req.getActor()))));
    }

    @Override
    public void recordAssessment(RecordAssessmentRequest req, StreamObserver<RecordReply> out) {
        respond(out, () -> reply(claims.recordAssessment(uuid(req.getClaimId()), new BigDecimal(req.getProposedAmount()),
                actor(req.getActor()))));
    }

    private static RecordReply reply(ClaimStatus status) {
        return RecordReply.newBuilder().setStatus(toProto(status)).build();
    }

    private static ClaimStatus toDomain(com.claims.proto.claim.v1.ClaimStatus s) {
        if (s == com.claims.proto.claim.v1.ClaimStatus.CLAIM_STATUS_UNSPECIFIED || s == com.claims.proto.claim.v1.ClaimStatus.UNRECOGNIZED) {
            throw new IllegalArgumentException("status is required");
        }
        return ClaimStatus.valueOf(s.name());
    }

    private static com.claims.proto.claim.v1.ClaimStatus toProto(ClaimStatus s) {
        return com.claims.proto.claim.v1.ClaimStatus.valueOf(s.name());
    }

    private static UUID uuid(String s) {
        return UUID.fromString(s);
    }

    /** A person if the actor has a UUID id, otherwise the calling service. */
    private static Actor actor(com.claims.proto.claim.v1.Actor a) {
        String role = a.getRole().isBlank() ? "SYSTEM" : a.getRole();
        try {
            return new Actor(UUID.fromString(a.getId()), role, a.getName());
        } catch (IllegalArgumentException notAUuid) {
            return new Actor(null, role, a.getName().isBlank() ? a.getId() : a.getName());
        }
    }

    private static <R> void respond(StreamObserver<R> out, Supplier<R> call) {
        R reply;
        try {
            reply = call.get();
        } catch (ApiException e) {
            Status status = switch (e.getStatus()) {
                case NOT_FOUND -> Status.NOT_FOUND;
                case CONFLICT -> Status.FAILED_PRECONDITION;
                case BAD_REQUEST -> Status.INVALID_ARGUMENT;
                default -> Status.INTERNAL;
            };
            out.onError(status.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (IllegalArgumentException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            log.error("ClaimService call failed", e);
            out.onError(Status.INTERNAL.withDescription("Claim service error").asRuntimeException());
            return;
        }
        out.onNext(reply);
        out.onCompleted();
    }
}
