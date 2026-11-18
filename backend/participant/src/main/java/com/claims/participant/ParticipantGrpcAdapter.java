package com.claims.participant;

import com.claims.proto.settlement.v1.AckReply;
import com.claims.proto.settlement.v1.CanCommitRequest;
import com.claims.proto.settlement.v1.SettlementParticipantGrpc;
import com.claims.proto.settlement.v1.TxRequest;
import com.claims.proto.settlement.v1.Vote;
import com.claims.proto.settlement.v1.VoteReply;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Serves a {@link ThreePhaseParticipant} as the {@code SettlementParticipant} gRPC service
 * (claims/settlement/v1/participant.proto). Errors: unknown tx → NOT_FOUND, contradicting message →
 * FAILED_PRECONDITION, malformed ids/amounts → INVALID_ARGUMENT, anything else → INTERNAL.
 */
public class ParticipantGrpcAdapter extends SettlementParticipantGrpc.SettlementParticipantImplBase {

    private static final Logger log = LoggerFactory.getLogger(ParticipantGrpcAdapter.class);

    private final ThreePhaseParticipant<?> participant;

    public ParticipantGrpcAdapter(ThreePhaseParticipant<?> participant) {
        this.participant = participant;
    }

    @Override
    public void canCommit(CanCommitRequest req, StreamObserver<VoteReply> out) {
        respond(out, () -> {
            BigDecimal amount = req.getAmount().isBlank() ? null : new BigDecimal(req.getAmount());
            VoteResult vote = participant.canCommit(UUID.fromString(req.getTxId()), UUID.fromString(req.getClaimId()),
                    amount, req.getAttempt());
            return VoteReply.newBuilder()
                    .setVote(vote.yes() ? Vote.VOTE_YES : Vote.VOTE_NO)
                    .setReason(vote.reason() == null ? "" : vote.reason())
                    .build();
        });
    }

    @Override
    public void preCommit(TxRequest req, StreamObserver<AckReply> out) {
        respond(out, () -> ack(participant.preCommit(UUID.fromString(req.getTxId()))));
    }

    @Override
    public void doCommit(TxRequest req, StreamObserver<AckReply> out) {
        respond(out, () -> ack(participant.doCommit(UUID.fromString(req.getTxId()))));
    }

    @Override
    public void abort(TxRequest req, StreamObserver<AckReply> out) {
        respond(out, () -> ack(participant.abort(UUID.fromString(req.getTxId()))));
    }

    private static AckReply ack(ParticipantTxState state) {
        return AckReply.newBuilder().setState(state.toProto()).build();
    }

    private <R> void respond(StreamObserver<R> out, Supplier<R> call) {
        R reply;
        try {
            reply = call.get();
        } catch (TxNotFoundException e) {
            out.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (IllegalTxStateException e) {
            out.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (IllegalArgumentException e) { // bad UUID or amount
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            return;
        } catch (RuntimeException e) {
            log.error("[{}] participant call failed", participant.name(), e);
            out.onError(Status.INTERNAL.withDescription("Participant error").asRuntimeException());
            return;
        }
        out.onNext(reply);
        out.onCompleted();
    }
}
