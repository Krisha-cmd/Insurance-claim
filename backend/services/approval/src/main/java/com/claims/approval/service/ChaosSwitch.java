package com.claims.approval.service;

import com.claims.approval.domain.CoordinatorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory and one-shot: a crash resets the mode to NONE (a real crash restarts the process anyway,
 * which also resets it), so the recovered coordinator does not crash again on the next settlement.
 */
@Component
public class ChaosSwitch {

    private static final Logger log = LoggerFactory.getLogger(ChaosSwitch.class);

    private final AtomicReference<ChaosMode> mode = new AtomicReference<>(ChaosMode.NONE);
    private final Halter halter;

    public ChaosSwitch(Halter halter) {
        this.halter = halter;
    }

    public void set(ChaosMode next) {
        ChaosMode previous = mode.getAndSet(next == null ? ChaosMode.NONE : next);
        log.warn("Chaos mode changed from {} to {}", previous, mode.get());
    }

    public ChaosMode mode() {
        return mode.get();
    }

    /** Whether reaching {@code state} would trigger the armed crash. */
    public boolean armedFor(CoordinatorState state) {
        return trigger(mode.get()) == state;
    }

    /**
     * Called by the coordinator right after it has finished the work of {@code reached}
     * (WAITING_VOTES: all votes in; PRECOMMITTING: logged and PreCommit acknowledged).
     *
     * @param lastWords runs just before the halt (used to publish a COORDINATOR_CRASHED event)
     */
    public void maybeCrash(CoordinatorState reached, Runnable lastWords) {
        ChaosMode armed = mode.get();
        if (trigger(armed) != reached || !mode.compareAndSet(armed, ChaosMode.NONE)) {
            return;
        }
        log.error("CHAOS {}: coordinator crashing in state {}", armed, reached);
        try {
            lastWords.run();
        } finally {
            halter.halt("chaos " + armed + " at " + reached);
        }
    }

    private static CoordinatorState trigger(ChaosMode mode) {
        return switch (mode) {
            case CRASH_BEFORE_PRECOMMIT -> CoordinatorState.WAITING_VOTES;
            case CRASH_AFTER_PRECOMMIT -> CoordinatorState.PRECOMMITTING;
            case NONE -> null;
        };
    }
}
