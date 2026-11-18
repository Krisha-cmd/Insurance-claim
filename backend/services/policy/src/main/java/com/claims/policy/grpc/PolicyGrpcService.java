package com.claims.policy.grpc;

import com.claims.common.error.ApiException;
import com.claims.policy.domain.InsuredMember;
import com.claims.policy.mapper.PolicyMapper;
import com.claims.policy.service.PolicyQueryService;
import com.claims.policy.service.PolicyQueryService.PolicyView;
import com.claims.proto.policy.v1.BalanceDetails;
import com.claims.proto.policy.v1.CoverageDetails;
import com.claims.proto.policy.v1.PolicyDetails;
import com.claims.proto.policy.v1.PolicyNoRequest;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import com.claims.proto.policy.v1.ValidateMemberReply;
import com.claims.proto.policy.v1.ValidateMemberRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/** PolicyService (claims/policy/v1/policy.proto). Unknown policy → NOT_FOUND. */
@Component
public class PolicyGrpcService extends PolicyServiceGrpc.PolicyServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PolicyGrpcService.class);

    private final PolicyQueryService queries;
    private final PolicyMapper mapper;

    public PolicyGrpcService(PolicyQueryService queries, PolicyMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    /** Claim Intake, when a claim is filed. Does not refuse a lapsed policy: Eligibility decides that. */
    @Override
    public void validateMember(ValidateMemberRequest req, StreamObserver<ValidateMemberReply> out) {
        respond(out, () -> {
            PolicyView v = queries.view(req.getPolicyNo());
            ValidateMemberReply.Builder reply = ValidateMemberReply.newBuilder()
                    .setPolicyStatus(v.policy().getStatus().name())
                    .setPolicyholderName(v.holderName());
            if (!v.policy().getPolicyholder().getId().toString().equals(req.getPolicyholderId())) {
                return reply.setValid(false).setReason("This policy belongs to someone else.").build();
            }
            Optional<InsuredMember> member = v.members().stream()
                    .filter(m -> m.getId().toString().equals(req.getMemberId())).findFirst();
            if (member.isEmpty()) {
                return reply.setValid(false).setReason("The patient is not insured under this policy.").build();
            }
            return reply.setValid(true).setMemberName(member.get().getFullName())
                    .setRelation(member.get().getRelation().name()).build();
        });
    }

    @Override
    public void getPolicy(PolicyNoRequest req, StreamObserver<PolicyDetails> out) {
        respond(out, () -> mapper.details(queries.view(req.getPolicyNo())));
    }

    @Override
    public void getCoverage(PolicyNoRequest req, StreamObserver<CoverageDetails> out) {
        respond(out, () -> mapper.coverageDetails(queries.coverage(req.getPolicyNo())));
    }

    @Override
    public void getBalance(PolicyNoRequest req, StreamObserver<BalanceDetails> out) {
        respond(out, () -> mapper.balance(queries.view(req.getPolicyNo())));
    }

    private static <R> void respond(StreamObserver<R> out, Supplier<R> call) {
        R reply;
        try {
            reply = call.get();
        } catch (ApiException e) {
            Status s = e.getStatus() == HttpStatus.NOT_FOUND ? Status.NOT_FOUND : Status.INTERNAL;
            out.onError(s.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            log.error("PolicyService call failed", e);
            out.onError(Status.INTERNAL.withDescription("Policy service error").asRuntimeException());
            return;
        }
        out.onNext(reply);
        out.onCompleted();
    }
}
