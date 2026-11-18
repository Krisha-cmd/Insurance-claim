package com.claims.approval.service;

import com.claims.approval.config.CoordinatorProperties;
import com.claims.approval.domain.CoordinatorState;
import com.claims.approval.domain.SettlementTx;
import com.claims.approval.repository.SettlementTxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Finishes settlements a crashed (or restarted) coordinator left unfinished, using only the log:
 * WAITING_VOTES → abort, PRECOMMITTING / COMMITTING → DoCommit, ABORTING → Abort.
 *
 * <p>Runs once at startup and then every {@code claims.coordinator.recovery-interval}. A tx is picked up
 * only after it has been idle for {@code recovery-stale-after}, and never while this process runs it.
 */
@Component
public class CoordinatorRecovery {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorRecovery.class);

    private final SettlementTxRepository txs;
    private final ThreePhaseCommitCoordinator coordinator;
    private final Clock clock;
    private final Duration staleAfter;

    public CoordinatorRecovery(SettlementTxRepository txs, ThreePhaseCommitCoordinator coordinator, Clock clock,
                               CoordinatorProperties props) {
        this.txs = txs;
        this.coordinator = coordinator;
        this.clock = clock;
        this.staleAfter = props.recoveryStaleAfter();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int n = recoverStale();
        if (n > 0) {
            log.warn("Startup recovery handled {} unfinished settlement(s)", n);
        }
    }

    @Scheduled(initialDelayString = "${claims.coordinator.recovery-interval}",
               fixedDelayString = "${claims.coordinator.recovery-interval}")
    public void sweep() {
        recoverStale();
    }

    /** @return how many unfinished settlements were handed to the coordinator */
    public int recoverStale() {
        List<SettlementTx> stale = txs.findByStateInAndUpdatedAtLessThanEqualOrderByUpdatedAt(
                CoordinatorState.UNFINISHED, clock.instant().minus(staleAfter));
        int handled = 0;
        for (SettlementTx t : stale) {
            if (coordinator.isInFlight(t.getTxId())) {
                continue;
            }
            coordinator.recover(t.getTxId());
            handled++;
        }
        return handled;
    }
}
