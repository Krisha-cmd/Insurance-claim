package com.claims.assessment.grpc;

import com.claims.assessment.config.AssessmentProperties;
import com.claims.assessment.service.Balance;
import com.claims.assessment.service.Coverage;
import com.claims.common.error.ApiException;
import com.claims.proto.policy.v1.BalanceDetails;
import com.claims.proto.policy.v1.CoverageDetails;
import com.claims.proto.policy.v1.PolicyNoRequest;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/** Policy's GetCoverage and GetBalance: the plan terms and remaining sum insured the calculation needs. */
@Component
public class PolicyClient {

    private final PolicyServiceGrpc.PolicyServiceBlockingStub stub;
    private final long timeoutMs;

    public PolicyClient(PolicyServiceGrpc.PolicyServiceBlockingStub policyStub, AssessmentProperties props) {
        this.stub = policyStub;
        this.timeoutMs = props.callTimeout().toMillis();
    }

    public Coverage coverage(String policyNo) {
        CoverageDetails c = call(() -> stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .getCoverage(PolicyNoRequest.newBuilder().setPolicyNo(policyNo).build()), policyNo);
        return new Coverage(decimal(c.getDeductible()), decimal(c.getCopayPct()), c.getRulesList().stream()
                .map(r -> new Coverage.Rule(r.getTreatmentCategory(), r.getTreatmentLabel(), r.getCovered(),
                        r.getSubLimit().isBlank() ? null : decimal(r.getSubLimit())))
                .toList());
    }

    public Balance balance(String policyNo) {
        BalanceDetails b = call(() -> stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .getBalance(PolicyNoRequest.newBuilder().setPolicyNo(policyNo).build()), policyNo);
        return new Balance(decimal(b.getRemaining()));
    }

    private interface Call<T> {
        T run();
    }

    private static <T> T call(Call<T> call, String policyNo) {
        try {
            return call.run();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ApiException(HttpStatus.CONFLICT, "Policy " + policyNo + " does not exist.");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The policy service is not available. Please try again shortly.");
        }
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The policy service returned an invalid amount '" + s + "'.");
        }
    }
}
