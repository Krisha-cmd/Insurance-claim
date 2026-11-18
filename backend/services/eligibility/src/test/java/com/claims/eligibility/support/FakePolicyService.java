package com.claims.eligibility.support;

import com.claims.proto.policy.v1.BalanceDetails;
import com.claims.proto.policy.v1.CoverageDetails;
import com.claims.proto.policy.v1.CoverageRule;
import com.claims.proto.policy.v1.Member;
import com.claims.proto.policy.v1.PolicyDetails;
import com.claims.proto.policy.v1.PolicyNoRequest;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Policy's GetPolicy / GetCoverage / GetBalance, answering exactly what Policy's seed (V2__seed.sql) holds. */
public class FakePolicyService extends PolicyServiceGrpc.PolicyServiceImplBase {

    public static final String ACTIVE = "POL-2026-00017";
    public static final String LAPSED = "POL-2026-00018";
    public static final String ASHA_00017 = "567dacf4-a6e5-461f-b094-8ded155a5a7e";
    public static final String VIKRAM_00017 = "7971f862-38fe-4a72-9193-f8d5a7bc591b";
    public static final String ASHA_00018 = "6ee0f2b2-1c47-43a7-b6f6-3af17479a96f";

    private final Map<String, PolicyDetails> policies = new ConcurrentHashMap<>();
    private final Map<String, String> remaining = new ConcurrentHashMap<>();
    private volatile boolean down;

    public FakePolicyService() {
        policies.put(ACTIVE, PolicyDetails.newBuilder().setPolicyNo(ACTIVE).setStatus("ACTIVE")
                .setStartDate("2026-01-01").setEndDate("2026-12-31").setSumInsured("500000.00").setPlanName("Family Health Plus")
                .addMembers(member(ASHA_00017, "Asha Verma", "SELF")).addMembers(member(VIKRAM_00017, "Vikram Verma", "SPOUSE"))
                .build());
        policies.put(LAPSED, PolicyDetails.newBuilder().setPolicyNo(LAPSED).setStatus("LAPSED")
                .setStartDate("2025-01-01").setEndDate("2025-12-31").setSumInsured("300000.00").setPlanName("Family Health Plus")
                .addMembers(member(ASHA_00018, "Asha Verma", "SELF"))
                .build());
        remaining.put(ACTIVE, "500000.00");
        remaining.put(LAPSED, "300000.00");
    }

    public void remaining(String policyNo, String amount) {
        remaining.put(policyNo, amount);
    }

    public void down(boolean down) {
        this.down = down;
    }

    private static Member member(String id, String name, String relation) {
        return Member.newBuilder().setMemberId(id).setFullName(name).setRelation(relation).build();
    }

    private static CoverageRule rule(String category, String label, boolean covered, String subLimit, int waitingDays) {
        return CoverageRule.newBuilder().setTreatmentCategory(category).setTreatmentLabel(label).setCovered(covered)
                .setSubLimit(subLimit).setWaitingPeriodDays(waitingDays).build();
    }

    private <T> boolean unavailable(StreamObserver<T> out) {
        if (down) out.onError(Status.UNAVAILABLE.asRuntimeException());
        return down;
    }

    private PolicyDetails find(String policyNo, StreamObserver<?> out) {
        PolicyDetails p = policies.get(policyNo);
        if (p == null) out.onError(Status.NOT_FOUND.withDescription("Unknown policy").asRuntimeException());
        return p;
    }

    @Override
    public void getPolicy(PolicyNoRequest req, StreamObserver<PolicyDetails> out) {
        if (unavailable(out)) return;
        PolicyDetails p = find(req.getPolicyNo(), out);
        if (p == null) return;
        out.onNext(p);
        out.onCompleted();
    }

    @Override
    public void getCoverage(PolicyNoRequest req, StreamObserver<CoverageDetails> out) {
        if (unavailable(out)) return;
        PolicyDetails p = find(req.getPolicyNo(), out);
        if (p == null) return;
        out.onNext(CoverageDetails.newBuilder().setPolicyNo(p.getPolicyNo()).setPlanName(p.getPlanName())
                .setDeductible("5000.00").setCopayPct("10.00").setRoomRentLimit("5000.00")
                .setStartDate(p.getStartDate()).setEndDate(p.getEndDate())
                .addRules(rule("HOSPITALISATION", "Hospitalisation", true, "300000.00", 0))
                .addRules(rule("SURGERY", "Surgery", true, "200000.00", 0))
                .addRules(rule("MATERNITY", "Maternity", true, "75000.00", 270))
                .addRules(rule("DAYCARE", "Day care procedure", true, "50000.00", 0))
                .addRules(rule("COSMETIC", "Cosmetic treatment", false, "", 0))
                .build());
        out.onCompleted();
    }

    @Override
    public void getBalance(PolicyNoRequest req, StreamObserver<BalanceDetails> out) {
        if (unavailable(out)) return;
        PolicyDetails p = find(req.getPolicyNo(), out);
        if (p == null) return;
        out.onNext(BalanceDetails.newBuilder().setPolicyNo(p.getPolicyNo()).setSumInsured(p.getSumInsured())
                .setRemaining(remaining.get(p.getPolicyNo())).build());
        out.onCompleted();
    }
}
