package com.claims.intake.grpc;

import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.eligibility.v1.CheckEligibilityReply;
import com.claims.proto.eligibility.v1.CheckEligibilityRequest;
import com.claims.proto.eligibility.v1.EligibilityServiceGrpc;
import com.claims.intake.config.IntakeProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class EligibilityClient {

    private final EligibilityServiceGrpc.EligibilityServiceBlockingStub stub;
    private final long timeoutMs;

    public EligibilityClient(EligibilityServiceGrpc.EligibilityServiceBlockingStub stub, IntakeProperties props) {
        this.stub = stub;
        this.timeoutMs = props.workflow().checkTimeout().toMillis();
    }

    /** Throws {@code StatusRuntimeException} on failure or after the deadline (5 s). */
    public CheckEligibilityReply check(ClaimDetails claim) {
        return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .check(CheckEligibilityRequest.newBuilder().setClaim(claim).build());
    }
}
