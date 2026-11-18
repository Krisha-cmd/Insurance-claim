package com.claims.intake.grpc;

import com.claims.common.error.ApiException;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import com.claims.proto.policy.v1.ValidateMemberReply;
import com.claims.proto.policy.v1.ValidateMemberRequest;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class PolicyClient {

    /** What a claim needs from Policy. */
    public record Member(String policyholderName, String memberName, String relation) {
    }

    private final PolicyServiceGrpc.PolicyServiceBlockingStub stub;

    public PolicyClient(PolicyServiceGrpc.PolicyServiceBlockingStub stub) {
        this.stub = stub;
    }

    /**
     * Checks that {@code memberId} is insured under {@code policyNo} and that the policy is the caller's.
     *
     * @throws ApiException 404 unknown policy, 400 not valid (with Policy's reason), 503 Policy unreachable
     */
    public Member validateMember(String policyNo, UUID memberId, UUID policyholderId) {
        ValidateMemberReply reply;
        try {
            reply = stub.withDeadlineAfter(3, TimeUnit.SECONDS).validateMember(ValidateMemberRequest.newBuilder()
                    .setPolicyNo(policyNo).setMemberId(memberId.toString()).setPolicyholderId(policyholderId.toString())
                    .build());
        } catch (StatusRuntimeException e) {
            throw switch (e.getStatus().getCode()) {
                case NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "Policy " + policyNo + " not found.");
                case PERMISSION_DENIED -> new ApiException(HttpStatus.FORBIDDEN, "This policy belongs to someone else.");
                default -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The policy service is not available. Please try again shortly.");
            };
        }
        if (!reply.getValid()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    reply.getReason().isBlank() ? "The patient is not covered by this policy." : reply.getReason());
        }
        return new Member(blankToNull(reply.getPolicyholderName()), reply.getMemberName(), blankToNull(reply.getRelation()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
