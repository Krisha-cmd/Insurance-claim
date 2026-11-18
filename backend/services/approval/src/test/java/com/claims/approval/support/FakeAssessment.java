package com.claims.approval.support;

import com.claims.proto.assessment.v1.AssessmentServiceGrpc;
import com.claims.proto.assessment.v1.FinalAmountReply;
import com.claims.proto.assessment.v1.GetFinalAmountRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Answers the amounts a test registered; FAILED_PRECONDITION ("not assessed") for anything else. */
public class FakeAssessment extends AssessmentServiceGrpc.AssessmentServiceImplBase {

    private final Map<String, String> amounts = new ConcurrentHashMap<>();

    public void assessed(UUID claimId, String amount) {
        amounts.put(claimId.toString(), amount);
    }

    @Override
    public void getFinalAmount(GetFinalAmountRequest req, StreamObserver<FinalAmountReply> out) {
        String amount = amounts.get(req.getClaimId());
        if (amount == null) {
            out.onError(Status.FAILED_PRECONDITION.withDescription("not assessed").asRuntimeException());
            return;
        }
        out.onNext(FinalAmountReply.newBuilder().setClaimId(req.getClaimId()).setAmount(amount).setCurrency("INR").build());
        out.onCompleted();
    }
}
