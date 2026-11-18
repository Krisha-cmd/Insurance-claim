package com.claims.approval.support;

import com.claims.proto.settlement.v1.AckReply;
import com.claims.proto.settlement.v1.CanCommitRequest;
import com.claims.proto.settlement.v1.ParticipantState;
import com.claims.proto.settlement.v1.SettlementParticipantGrpc;
import com.claims.proto.settlement.v1.TxRequest;
import com.claims.proto.settlement.v1.Vote;
import com.claims.proto.settlement.v1.VoteReply;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-process 3PC participant whose behaviour each test can change. Records every call. */
public class FakeParticipant extends SettlementParticipantGrpc.SettlementParticipantImplBase {

    public record Call(String method, String txId) {
    }

    private final String name;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, ParticipantState> states = new ConcurrentHashMap<>();

    private volatile String noReason;          // non-null: vote NO
    private volatile long canCommitDelayMs;    // > phase timeout: simulate a hang
    private volatile boolean doCommitUnavailable;

    public FakeParticipant(String name) {
        this.name = name;
    }

    public String name() { return name; }

    public void reset() {
        noReason = null;
        canCommitDelayMs = 0;
        doCommitUnavailable = false;
    }

    public void voteNo(String reason) { this.noReason = reason; }

    public void delayCanCommit(long ms) { this.canCommitDelayMs = ms; }

    public void failDoCommit(boolean unavailable) { this.doCommitUnavailable = unavailable; }

    /** Methods called for one tx, in order, e.g. [CanCommit, PreCommit, DoCommit]. */
    public List<String> callsFor(Object txId) {
        return calls.stream().filter(c -> c.txId().equals(txId.toString())).map(Call::method).toList();
    }

    public ParticipantState stateOf(Object txId) {
        return states.getOrDefault(txId.toString(), ParticipantState.PARTICIPANT_STATE_UNSPECIFIED);
    }

    @Override
    public void canCommit(CanCommitRequest req, StreamObserver<VoteReply> out) {
        calls.add(new Call("CanCommit", req.getTxId()));
        sleep(canCommitDelayMs);
        if (noReason != null || states.get(req.getTxId()) == ParticipantState.ABORTED) {
            states.put(req.getTxId(), ParticipantState.ABORTED);
            reply(out, VoteReply.newBuilder().setVote(Vote.VOTE_NO).setReason(noReason == null ? "already aborted" : noReason).build());
            return;
        }
        states.put(req.getTxId(), ParticipantState.PREPARED);
        reply(out, VoteReply.newBuilder().setVote(Vote.VOTE_YES).build());
    }

    @Override
    public void preCommit(TxRequest req, StreamObserver<AckReply> out) {
        calls.add(new Call("PreCommit", req.getTxId()));
        states.put(req.getTxId(), ParticipantState.PRECOMMITTED);
        reply(out, ack(req));
    }

    @Override
    public void doCommit(TxRequest req, StreamObserver<AckReply> out) {
        calls.add(new Call("DoCommit", req.getTxId()));
        if (doCommitUnavailable) {
            out.onError(Status.UNAVAILABLE.withDescription(name + " is down").asRuntimeException());
            return;
        }
        states.put(req.getTxId(), ParticipantState.COMMITTED);
        reply(out, ack(req));
    }

    @Override
    public void abort(TxRequest req, StreamObserver<AckReply> out) {
        calls.add(new Call("Abort", req.getTxId()));
        if (states.get(req.getTxId()) == ParticipantState.COMMITTED) {
            out.onError(Status.FAILED_PRECONDITION.withDescription("already committed").asRuntimeException());
            return;
        }
        states.put(req.getTxId(), ParticipantState.ABORTED);
        reply(out, ack(req));
    }

    private AckReply ack(TxRequest req) {
        return AckReply.newBuilder().setState(states.get(req.getTxId())).build();
    }

    private static <T> void reply(StreamObserver<T> out, T value) {
        out.onNext(value);
        out.onCompleted();
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
