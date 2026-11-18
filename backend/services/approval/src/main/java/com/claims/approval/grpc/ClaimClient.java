package com.claims.approval.grpc;

import com.claims.approval.config.ApprovalProperties;
import com.claims.common.error.ApiException;
import com.claims.common.event.ClaimEvent;
import com.claims.proto.claim.v1.Actor;
import com.claims.proto.claim.v1.ClaimServiceGrpc;
import com.claims.proto.claim.v1.ClaimStatus;
import com.claims.proto.claim.v1.UpdateStatusReply;
import com.claims.proto.claim.v1.UpdateStatusRequest;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Claim status lives in Claim Intake; this is the only way Approval changes it. */
@Component
public class ClaimClient {

    private final ManagedChannel channel;
    private final ClaimServiceGrpc.ClaimServiceBlockingStub stub;
    private final long timeoutMs;

    public ClaimClient(ApprovalProperties props, GrpcChannelFactory channelFactory) {
        this.channel = channelFactory.create(props.claimTarget());
        this.stub = ClaimServiceGrpc.newBlockingStub(channel);
        this.timeoutMs = props.callTimeout().toMillis();
    }

    /**
     * Idempotent on Intake's side: setting the status a claim already has is a no-op, so callers may retry
     * (and both the coordinator and Intake's own abort handler may set SETTLEMENT_FAILED).
     *
     * @return the previous status
     */
    public ClaimStatus updateStatus(UUID claimId, ClaimStatus status, String reason, ClaimEvent.Actor actor,
                                    String idempotencyKey) {
        UpdateStatusRequest request = UpdateStatusRequest.newBuilder()
                .setClaimId(claimId.toString())
                .setStatus(status)
                .setReason(reason == null ? "" : reason)
                .setActor(Actor.newBuilder().setId(actor.id()).setRole(actor.role()).setName(actor.name() == null ? "" : actor.name()))
                .setIdempotencyKey(idempotencyKey)
                .build();
        try {
            UpdateStatusReply reply = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).updateStatus(request);
            return reply.getPreviousStatus();
        } catch (StatusRuntimeException e) {
            String detail = e.getStatus().getDescription();
            throw switch (e.getStatus().getCode()) {
                case NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "Claim " + claimId + " not found.");
                case FAILED_PRECONDITION -> new ApiException(HttpStatus.CONFLICT,
                        detail != null && !detail.isBlank() ? detail : "The claim cannot move to " + status + " from its current status.");
                default -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The claim service is not available. Please try again shortly.");
            };
        }
    }

    @PreDestroy
    void close() {
        channel.shutdown();
    }
}
