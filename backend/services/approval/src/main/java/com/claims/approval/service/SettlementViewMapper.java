package com.claims.approval.service;

import com.claims.approval.api.dto.ParticipantView;
import com.claims.approval.api.dto.SettlementView;
import com.claims.approval.api.dto.SettlementView.LogEntry;
import com.claims.approval.domain.CoordinatorState;
import com.claims.approval.domain.SettlementTx;
import com.claims.approval.domain.SettlementTxParticipant;
import com.claims.approval.domain.TxState;
import com.claims.approval.domain.Vote;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class SettlementViewMapper {

    public SettlementView toView(SettlementTx tx, List<SettlementTxParticipant> rows) {
        CoordinatorState state = tx.getState();
        String outcome = switch (state) {
            case COMMITTED -> "COMMITTED";
            case ABORTED -> "ABORTED";
            default -> "IN_PROGRESS";
        };
        String abortReason = null;
        if (state == CoordinatorState.ABORTED || state == CoordinatorState.ABORTING) {
            String fromRows = ThreePhaseCommitCoordinator.abortReason(rows);
            abortReason = fromRows != null ? fromRows : ThreePhaseCommitCoordinator.RESTART_ABORT_REASON;
        }

        List<ParticipantView> participants = rows.stream().map(this::participant).toList();
        return new SettlementView(tx.getTxId(), tx.getClaimId(), tx.getAttempt(), state.name(), state.phase(),
                outcome, "UP", tx.getAmount(), participants, tx.getCreatedAt(),
                state.isFinal() ? tx.getUpdatedAt() : null, abortReason, log(tx, rows, abortReason));
    }

    private ParticipantView participant(SettlementTxParticipant p) {
        String vote = p.getVote() != null ? p.getVote().name()
                : p.getError() != null ? "NO"        // no vote within the deadline counts as NO
                : "PENDING";
        TxState s = p.getState();
        return new ParticipantView(displayName(p.getParticipant()), p.getParticipant(), vote, s.name(),
                s == TxState.PRECOMMITTED || s == TxState.COMMITTED,
                s == TxState.COMMITTED,
                s == TxState.ABORTED,
                p.getLastContactAt(), p.getError());
    }

    /** claim-intake → CLAIM_INTAKE, the keys the frontend labels. */
    static String displayName(String participant) {
        return participant.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** Rebuilds a short history: start, each participant's latest known step, and the outcome. */
    private List<LogEntry> log(SettlementTx tx, List<SettlementTxParticipant> rows, String abortReason) {
        List<LogEntry> log = new ArrayList<>();
        log.add(new LogEntry(tx.getCreatedAt(), "Coordinator logged WAITING_VOTES for attempt " + tx.getAttempt()
                + " and sent CanCommit to " + rows.size() + " participants", "info"));

        for (SettlementTxParticipant p : rows) {
            String who = displayName(p.getParticipant());
            Instant at = p.getLastContactAt() != null ? p.getLastContactAt() : tx.getUpdatedAt();
            if (p.getVote() == Vote.NO) {
                log.add(new LogEntry(at, who + " voted NO: " + p.getError(), "error"));
            } else if (p.getVote() == null && p.getError() != null) {
                log.add(new LogEntry(at, who + " did not vote: " + p.getError(), "error"));
            } else {
                String step = switch (p.getState()) {
                    case PREPARED -> "voted YES";
                    case PRECOMMITTED -> "voted YES and acknowledged PreCommit";
                    case COMMITTED -> "committed and acknowledged";
                    case ABORTED -> "aborted and acknowledged";
                    case UNKNOWN -> null;
                };
                if (step != null) log.add(new LogEntry(at, who + " " + step, "info"));
                if (p.getError() != null) log.add(new LogEntry(at, who + ": " + p.getError(), "warn"));
            }
        }

        switch (tx.getState()) {
            case COMMITTED -> log.add(new LogEntry(tx.getUpdatedAt(), "Transaction committed by all participants", "info"));
            case ABORTED -> log.add(new LogEntry(tx.getUpdatedAt(), "Coordinator logged ABORTED: " + abortReason, "error"));
            case PRECOMMITTING -> log.add(new LogEntry(tx.getUpdatedAt(), "All participants voted YES. Coordinator logged PRECOMMITTING and sent PreCommit", "info"));
            case COMMITTING -> log.add(new LogEntry(tx.getUpdatedAt(), "Coordinator logged COMMITTING and sent DoCommit", "info"));
            case ABORTING -> log.add(new LogEntry(tx.getUpdatedAt(), "Coordinator logged ABORTING and sent Abort", "warn"));
            case WAITING_VOTES -> { }
        }
        log.sort(Comparator.comparing(LogEntry::at));
        return log;
    }
}
