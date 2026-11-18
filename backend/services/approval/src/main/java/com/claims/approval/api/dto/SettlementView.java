package com.claims.approval.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/approvals/{claimId}/settlement: the latest settlement attempt, in the shape the frontend's
 * settlement stepper (SettlementTab.jsx) reads.
 *
 * @param state       coordinator state as logged (WAITING_VOTES … ABORTED)
 * @param phase       CAN_COMMIT, PRE_COMMIT, DO_COMMIT or DONE
 * @param outcome     IN_PROGRESS, COMMITTED or ABORTED
 * @param coordinator UP (this service answered, so it is running)
 * @param log         a readable history rebuilt from the coordinator log; the full step-by-step trail is
 *                    the TX_* events on claim-events
 */
public record SettlementView(UUID transactionId, UUID claimId, int attempt, String state, String phase,
                             String outcome, String coordinator, BigDecimal amount,
                             List<ParticipantView> participants, Instant startedAt, Instant finishedAt,
                             String abortReason, List<LogEntry> log) {

    /** @param level info, warn or error */
    public record LogEntry(Instant at, String message, String level) {
    }
}
