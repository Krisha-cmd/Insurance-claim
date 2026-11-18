package com.claims.eligibility.grpc;

import com.claims.common.error.ApiException;
import com.claims.eligibility.config.EligibilityProperties;
import com.claims.eligibility.rules.RuleContext;
import com.claims.proto.policy.v1.BalanceDetails;
import com.claims.proto.policy.v1.CoverageDetails;
import com.claims.proto.policy.v1.PolicyDetails;
import com.claims.proto.policy.v1.PolicyNoRequest;
import com.claims.proto.policy.v1.PolicyServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Policy's GetPolicy, GetCoverage and GetBalance, as the facts the rules read. */
@Component
public class PolicyClient {

    private final PolicyServiceGrpc.PolicyServiceBlockingStub stub;
    private final long timeoutMs;

    public PolicyClient(PolicyServiceGrpc.PolicyServiceBlockingStub policyStub, EligibilityProperties props) {
        this.stub = policyStub;
        this.timeoutMs = props.callTimeout().toMillis();
    }

    public RuleContext.PolicyFacts policy(String policyNo) {
        PolicyDetails p = call(() -> deadline().getPolicy(req(policyNo)), policyNo);
        Map<UUID, String> members = new LinkedHashMap<>();
        p.getMembersList().forEach(m -> {
            UUID id = uuidOrNull(m.getMemberId());
            if (id != null) members.put(id, m.getFullName());
        });
        return new RuleContext.PolicyFacts(p.getPolicyNo(), p.getStatus(), date(p.getStartDate()), date(p.getEndDate()), members);
    }

    public RuleContext.Coverage coverage(String policyNo) {
        CoverageDetails c = call(() -> deadline().getCoverage(req(policyNo)), policyNo);
        return new RuleContext.Coverage(c.getRulesList().stream()
                .map(r -> new RuleContext.Rule(r.getTreatmentCategory(), r.getTreatmentLabel(), r.getCovered(),
                        r.getWaitingPeriodDays()))
                .toList());
    }

    public RuleContext.Balance balance(String policyNo) {
        BalanceDetails b = call(() -> deadline().getBalance(req(policyNo)), policyNo);
        try {
            return new RuleContext.Balance(b.getRemaining().isBlank() ? null : new BigDecimal(b.getRemaining()));
        } catch (NumberFormatException e) {
            return new RuleContext.Balance(null); // BalanceAvailableRule fails with "unknown"
        }
    }

    private PolicyServiceGrpc.PolicyServiceBlockingStub deadline() {
        return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private static PolicyNoRequest req(String policyNo) {
        return PolicyNoRequest.newBuilder().setPolicyNo(policyNo).build();
    }

    private static <T> T call(Supplier<T> call, String policyNo) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ApiException(HttpStatus.CONFLICT, "Policy " + policyNo + " does not exist.");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The policy service is not available. Please try again shortly.");
        }
    }

    static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static UUID uuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
