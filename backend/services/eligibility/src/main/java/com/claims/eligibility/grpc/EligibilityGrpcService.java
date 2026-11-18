package com.claims.eligibility.grpc;

import com.claims.common.error.ApiException;
import com.claims.eligibility.service.EligibilityService;
import com.claims.proto.claim.v1.RuleResult;
import com.claims.proto.eligibility.v1.CheckEligibilityReply;
import com.claims.proto.eligibility.v1.CheckEligibilityRequest;
import com.claims.proto.eligibility.v1.EligibilityServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * EligibilityService.Check (claims/eligibility/v1/eligibility.proto), called by Claim Intake's workflow with the
 * claim's details. Claim Intake records the result and emits ELIGIBILITY_CHECKED, so nothing is stored or
 * emitted here.
 */
@Component
public class EligibilityGrpcService extends EligibilityServiceGrpc.EligibilityServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(EligibilityGrpcService.class);

    private final EligibilityService service;

    public EligibilityGrpcService(EligibilityService service) {
        this.service = service;
    }

    @Override
    public void check(CheckEligibilityRequest req, StreamObserver<CheckEligibilityReply> out) {
        CheckEligibilityReply reply;
        try {
            EligibilityService.Evaluation e = service.check(req.getClaim());
            CheckEligibilityReply.Builder b = CheckEligibilityReply.newBuilder()
                    .setEligible(e.eligible()).setCheckedAt(e.at().toString());
            e.results().forEach(r -> b.addRules(RuleResult.newBuilder()
                    .setRule(r.rule()).setLabel(r.label()).setPassed(r.passed()).setDetail(r.message()).build()));
            reply = b.build();
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
            log.error("EligibilityService.Check failed", e);
            out.onError(Status.INTERNAL.withDescription("Eligibility service error").asRuntimeException());
            return;
        }
        out.onNext(reply);
        out.onCompleted();
    }
}
