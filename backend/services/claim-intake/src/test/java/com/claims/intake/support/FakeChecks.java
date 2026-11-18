package com.claims.intake.support;

import com.claims.proto.assessment.v1.AssessReply;
import com.claims.proto.assessment.v1.AssessRequest;
import com.claims.proto.assessment.v1.AssessmentServiceGrpc;
import com.claims.proto.assessment.v1.FinalAmountReply;
import com.claims.proto.assessment.v1.GetFinalAmountRequest;
import com.claims.proto.claim.v1.RuleResult;
import com.claims.proto.eligibility.v1.CheckEligibilityReply;
import com.claims.proto.eligibility.v1.CheckEligibilityRequest;
import com.claims.proto.eligibility.v1.EligibilityServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Eligibility and Assessment stand-ins. Each records when its calls started and ended. */
public final class FakeChecks {

    private FakeChecks() {
    }

    public record Call(String claimId, long startNanos, long endNanos) {
    }

    public static class Eligibility extends EligibilityServiceGrpc.EligibilityServiceImplBase {
        public volatile boolean eligible = true;
        public volatile boolean unavailable = false;
        public volatile long delayMs = 0;
        public final List<Call> calls = new CopyOnWriteArrayList<>();

        public void reset() { eligible = true; unavailable = false; delayMs = 0; }

        @Override
        public void check(CheckEligibilityRequest req, StreamObserver<CheckEligibilityReply> out) {
            long start = System.nanoTime();
            sleep(delayMs);
            calls.add(new Call(req.getClaim().getClaimId(), start, System.nanoTime()));
            if (unavailable) {
                out.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }
            out.onNext(CheckEligibilityReply.newBuilder().setEligible(eligible)
                    .addRules(RuleResult.newBuilder().setRule("POLICY_ACTIVE").setLabel("Policy active on admission date").setPassed(true).setDetail("Valid"))
                    .addRules(RuleResult.newBuilder().setRule("WAITING_PERIOD").setLabel("Waiting period completed").setPassed(eligible).setDetail("30 days"))
                    .build());
            out.onCompleted();
        }
    }

    public static class Assessment extends AssessmentServiceGrpc.AssessmentServiceImplBase {
        public volatile String amount = "67500.00";
        public volatile long delayMs = 0;
        public final List<Call> calls = new CopyOnWriteArrayList<>();

        public void reset() { amount = "67500.00"; delayMs = 0; }

        @Override
        public void assess(AssessRequest req, StreamObserver<AssessReply> out) {
            long start = System.nanoTime();
            sleep(delayMs);
            calls.add(new Call(req.getClaim().getClaimId(), start, System.nanoTime()));
            out.onNext(AssessReply.newBuilder().setProposedAmount(amount).setCurrency("INR").build());
            out.onCompleted();
        }

        @Override
        public void getFinalAmount(GetFinalAmountRequest req, StreamObserver<FinalAmountReply> out) {
            out.onError(Status.UNIMPLEMENTED.asRuntimeException());
        }
    }

    static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
