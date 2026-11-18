package com.claims.assessment.grpc;

import com.claims.assessment.domain.Assessment;
import com.claims.assessment.mapper.AssessmentMapper;
import com.claims.assessment.service.AssessmentService;
import com.claims.common.error.ApiException;
import com.claims.proto.assessment.v1.AssessReply;
import com.claims.proto.assessment.v1.AssessRequest;
import com.claims.proto.assessment.v1.AssessmentServiceGrpc;
import com.claims.proto.assessment.v1.FinalAmountReply;
import com.claims.proto.assessment.v1.GetFinalAmountRequest;
import com.claims.proto.claim.v1.ClaimDetails;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * AssessmentService (claims/assessment/v1/assessment.proto). Assess is called by Claim Intake with the claim's
 * details; GetFinalAmount by Approval before it settles.
 */
@Component
public class AssessmentGrpcService extends AssessmentServiceGrpc.AssessmentServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AssessmentGrpcService.class);

    private final AssessmentService service;
    private final AssessmentMapper mapper;

    public AssessmentGrpcService(AssessmentService service, AssessmentMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void assess(AssessRequest req, StreamObserver<AssessReply> out) {
        respond(out, () -> {
            ClaimDetails c = req.getClaim();
            if (c.getPolicyNo().isBlank()) throw invalid("claim.policy_no is required");
            Assessment.ClaimFacts facts = new Assessment.ClaimFacts(uuid(c.getClaimId(), "claim.claim_id"),
                    c.getPolicyNo(), c.getTreatmentCategory(), optionalUuid(c.getPolicyholderId()),
                    optionalUuid(c.getSubmittedBy()));
            return mapper.assessReply(service.assess(facts, amount(c.getClaimedAmount())));
        });
    }

    /** NOT_FOUND until the claim has been assessed (Approval answers 409 "not assessed yet"). */
    @Override
    public void getFinalAmount(GetFinalAmountRequest req, StreamObserver<FinalAmountReply> out) {
        respond(out, () -> mapper.finalAmount(service.forSettlement(uuid(req.getClaimId(), "claim_id"))));
    }

    private static <R> void respond(StreamObserver<R> out, Supplier<R> call) {
        R reply;
        try {
            reply = call.get();
        } catch (ApiException e) {
            Status s = switch (e.getStatus()) {
                case BAD_REQUEST -> Status.INVALID_ARGUMENT;
                case NOT_FOUND -> Status.NOT_FOUND;
                case CONFLICT -> Status.FAILED_PRECONDITION;
                case SERVICE_UNAVAILABLE, BAD_GATEWAY -> Status.UNAVAILABLE;
                default -> Status.INTERNAL;
            };
            out.onError(s.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            log.error("AssessmentService call failed", e);
            out.onError(Status.INTERNAL.withDescription("Assessment service error").asRuntimeException());
            return;
        }
        out.onNext(reply);
        out.onCompleted();
    }

    private static UUID uuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be a UUID");
        }
    }

    private static UUID optionalUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigDecimal amount(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (RuntimeException e) {
            throw invalid("claim.claimed_amount must be a decimal number");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }
}
