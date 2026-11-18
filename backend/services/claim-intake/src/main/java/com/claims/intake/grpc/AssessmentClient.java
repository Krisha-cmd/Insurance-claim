package com.claims.intake.grpc;

import com.claims.intake.config.IntakeProperties;
import com.claims.proto.assessment.v1.AssessReply;
import com.claims.proto.assessment.v1.AssessRequest;
import com.claims.proto.assessment.v1.AssessmentServiceGrpc;
import com.claims.proto.claim.v1.ClaimDetails;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AssessmentClient {

    private final AssessmentServiceGrpc.AssessmentServiceBlockingStub stub;
    private final long timeoutMs;

    public AssessmentClient(AssessmentServiceGrpc.AssessmentServiceBlockingStub stub, IntakeProperties props) {
        this.stub = stub;
        this.timeoutMs = props.workflow().checkTimeout().toMillis();
    }

    /** Throws {@code StatusRuntimeException} on failure or after the deadline (5 s). */
    public AssessReply assess(ClaimDetails claim) {
        return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .assess(AssessRequest.newBuilder().setClaim(claim).build());
    }
}
