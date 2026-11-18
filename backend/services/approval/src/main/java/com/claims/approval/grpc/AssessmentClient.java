package com.claims.approval.grpc;

import com.claims.approval.config.ApprovalProperties;
import com.claims.common.error.ApiException;
import com.claims.proto.assessment.v1.AssessmentServiceGrpc;
import com.claims.proto.assessment.v1.FinalAmountReply;
import com.claims.proto.assessment.v1.GetFinalAmountRequest;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class AssessmentClient {

    private final ManagedChannel channel;
    private final AssessmentServiceGrpc.AssessmentServiceBlockingStub stub;
    private final long timeoutMs;

    public AssessmentClient(ApprovalProperties props, GrpcChannelFactory channelFactory) {
        this.channel = channelFactory.create(props.assessmentTarget());
        this.stub = AssessmentServiceGrpc.newBlockingStub(channel);
        this.timeoutMs = props.callTimeout().toMillis();
    }

    /** The payable amount after any adjuster adjustment. */
    public BigDecimal getFinalAmount(UUID claimId) {
        try {
            FinalAmountReply reply = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                    .getFinalAmount(GetFinalAmountRequest.newBuilder().setClaimId(claimId.toString()).build());
            BigDecimal amount = new BigDecimal(reply.getAmount());
            if (amount.signum() <= 0) {
                throw new ApiException(HttpStatus.CONFLICT, "The assessed amount is zero, so there is nothing to settle. Reject the claim instead.");
            }
            return amount;
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The assessment service returned an invalid amount.");
        } catch (StatusRuntimeException e) {
            throw switch (e.getStatus().getCode()) {
                case NOT_FOUND, FAILED_PRECONDITION ->
                        new ApiException(HttpStatus.CONFLICT, "The claim has not been assessed yet.");
                default -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The assessment service is not available. Please try again shortly.");
            };
        }
    }

    @PreDestroy
    void close() {
        channel.shutdown();
    }
}
