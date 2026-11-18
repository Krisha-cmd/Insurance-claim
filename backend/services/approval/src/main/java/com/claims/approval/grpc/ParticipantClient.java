package com.claims.approval.grpc;

import com.claims.proto.settlement.v1.AckReply;
import com.claims.proto.settlement.v1.CanCommitRequest;
import com.claims.proto.settlement.v1.SettlementParticipantGrpc;
import com.claims.proto.settlement.v1.TxRequest;
import com.claims.proto.settlement.v1.VoteReply;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Blocking calls to one 3PC participant. Every call carries a deadline of one phase timeout;
 * on expiry it throws {@code StatusRuntimeException(DEADLINE_EXCEEDED)}.
 */
public class ParticipantClient {

    private final String name;
    private final SettlementParticipantGrpc.SettlementParticipantBlockingStub stub;
    private final Duration deadline;

    public ParticipantClient(String name, SettlementParticipantGrpc.SettlementParticipantBlockingStub stub, Duration deadline) {
        this.name = name;
        this.stub = stub;
        this.deadline = deadline;
    }

    public String name() {
        return name;
    }

    public VoteReply canCommit(CanCommitRequest request) {
        return withDeadline().canCommit(request);
    }

    public AckReply preCommit(UUID txId) {
        return withDeadline().preCommit(tx(txId));
    }

    public AckReply doCommit(UUID txId) {
        return withDeadline().doCommit(tx(txId));
    }

    public AckReply abort(UUID txId) {
        return withDeadline().abort(tx(txId));
    }

    private SettlementParticipantGrpc.SettlementParticipantBlockingStub withDeadline() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static TxRequest tx(UUID txId) {
        return TxRequest.newBuilder().setTxId(txId.toString()).build();
    }
}
