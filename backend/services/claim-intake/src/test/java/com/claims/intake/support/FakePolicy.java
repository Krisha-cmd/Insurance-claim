package com.claims.intake.support;

import com.claims.proto.policy.v1.PolicyServiceGrpc;
import com.claims.proto.policy.v1.ValidateMemberReply;
import com.claims.proto.policy.v1.ValidateMemberRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Knows the policies tests register: policyNo → (holder, member). */
public class FakePolicy extends PolicyServiceGrpc.PolicyServiceImplBase {

    public record Member(String policyholderId, String memberId, String memberName, String relation) {
    }

    private final Map<String, Member> policies = new ConcurrentHashMap<>();

    public void policy(String policyNo, Member member) {
        policies.put(policyNo, member);
    }

    @Override
    public void validateMember(ValidateMemberRequest req, StreamObserver<ValidateMemberReply> out) {
        Member m = policies.get(req.getPolicyNo());
        if (m == null) {
            out.onError(Status.NOT_FOUND.asRuntimeException());
            return;
        }
        ValidateMemberReply.Builder reply = ValidateMemberReply.newBuilder().setPolicyStatus("ACTIVE");
        if (!m.policyholderId().equals(req.getPolicyholderId())) {
            reply.setValid(false).setReason("This policy belongs to someone else.");
        } else if (!m.memberId().equals(req.getMemberId())) {
            reply.setValid(false).setReason("The patient is not insured under this policy.");
        } else {
            reply.setValid(true).setPolicyholderName("Asha Verma").setMemberName(m.memberName()).setRelation(m.relation());
        }
        out.onNext(reply.build());
        out.onCompleted();
    }
}
