package com.claims.approval.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** What the coordinator knows about one participant in one settlement. */
@Entity
@Table(name = "settlement_tx_participant")
public class SettlementTxParticipant {

    @Embeddable
    public record Key(@Column(name = "tx_id") UUID txId,
                      @Column(name = "participant") String participant) implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    private Vote vote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TxState state;

    @Column(name = "last_contact_at")
    private Instant lastContactAt;

    @Column(length = 500)
    private String error;

    protected SettlementTxParticipant() {
    }

    public SettlementTxParticipant(UUID txId, String participant) {
        this.id = new Key(txId, participant);
        this.state = TxState.UNKNOWN;
    }

    public void recordVote(Vote vote, String reason, Instant at) {
        this.vote = vote;
        this.state = vote == Vote.YES ? TxState.PREPARED : state;
        this.error = vote == Vote.NO ? truncate(reason == null || reason.isBlank() ? "Voted NO" : reason) : null;
        this.lastContactAt = at;
    }

    public void recordAck(TxState acknowledged, Instant at) {
        this.state = acknowledged;
        // A later ack supersedes a transient PreCommit/DoCommit failure, but the reason a vote was NO
        // (or never arrived) is kept: it explains why the settlement aborted.
        if (vote == Vote.YES) {
            this.error = null;
        }
        this.lastContactAt = at;
    }

    /** No reply (timeout, unreachable, error). Does not change the last acknowledged state. */
    public void recordFailure(String message) {
        this.error = truncate(message);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    public UUID getTxId() { return id.txId(); }
    public String getParticipant() { return id.participant(); }
    public Vote getVote() { return vote; }
    public TxState getState() { return state; }
    public Instant getLastContactAt() { return lastContactAt; }
    public String getError() { return error; }
}
