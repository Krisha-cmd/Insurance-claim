package com.claims.assessment.support;

import com.claims.proto.policy.v1.BalanceDetails;
import com.claims.proto.policy.v1.CoverageDetails;
import com.claims.proto.policy.v1.CoverageRule;
import com.claims.proto.policy.v1.PolicyNoRequest;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Policy's GetCoverage / GetBalance with per-policy plans. {@link #newPolicy()} registers the seeded plan
 * ("Family Health Plus": deductible 5000, co-pay 10%, Hospitalisation 300000, Day care 50000, Cosmetic not covered)
 * under a fresh policy number, so tests never share a balance.
 */
public class FakePolicyService extends PolicyServiceGrpc.PolicyServiceImplBase {

    private final Map<String, CoverageDetails> coverage = new ConcurrentHashMap<>();
    private final Map<String, String> remaining = new ConcurrentHashMap<>();
    public final AtomicInteger calls = new AtomicInteger();
    private volatile boolean down;

    public String newPolicy() {
        String policyNo = "POL-T-" + UUID.randomUUID().toString().substring(0, 8);
        coverage.put(policyNo, CoverageDetails.newBuilder()
                .setPolicyNo(policyNo).setPlanName("Family Health Plus")
                .setDeductible("5000.00").setCopayPct("10.00").setRoomRentLimit("5000.00")
                .addRules(rule("HOSPITALISATION", "Hospitalisation", true, "300000.00"))
                .addRules(rule("SURGERY", "Surgery", true, "200000.00"))
                .addRules(rule("DAYCARE", "Day care procedure", true, "50000.00"))
                .addRules(rule("COSMETIC", "Cosmetic treatment", false, ""))
                .build());
        remaining.put(policyNo, "500000.00");
        return policyNo;
    }

    public void remaining(String policyNo, String amount) {
        remaining.put(policyNo, amount);
    }

    public void down(boolean down) {
        this.down = down;
    }

    private static CoverageRule rule(String category, String label, boolean covered, String subLimit) {
        return CoverageRule.newBuilder().setTreatmentCategory(category).setTreatmentLabel(label)
                .setCovered(covered).setSubLimit(subLimit).build();
    }

    @Override
    public void getCoverage(PolicyNoRequest req, StreamObserver<CoverageDetails> out) {
        calls.incrementAndGet();
        if (down) {
            out.onError(Status.UNAVAILABLE.asRuntimeException());
            return;
        }
        CoverageDetails c = coverage.get(req.getPolicyNo());
        if (c == null) {
            out.onError(Status.NOT_FOUND.withDescription("Unknown policy").asRuntimeException());
            return;
        }
        out.onNext(c);
        out.onCompleted();
    }

    @Override
    public void getBalance(PolicyNoRequest req, StreamObserver<BalanceDetails> out) {
        calls.incrementAndGet();
        if (down) {
            out.onError(Status.UNAVAILABLE.asRuntimeException());
            return;
        }
        String r = remaining.get(req.getPolicyNo());
        if (r == null) {
            out.onError(Status.NOT_FOUND.withDescription("Unknown policy").asRuntimeException());
            return;
        }
        out.onNext(BalanceDetails.newBuilder().setPolicyNo(req.getPolicyNo()).setRemaining(r).build());
        out.onCompleted();
    }
}
