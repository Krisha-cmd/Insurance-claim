package com.claims.intake.domain;

import com.claims.common.error.ApiException;
import com.claims.intake.repository.ClaimRepository;
import com.claims.intake.repository.ClaimStatusHistoryRepository;
import com.claims.intake.service.IntakeEvents;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.claims.intake.domain.ClaimStatus.*;

/**
 * The only way a claim's status changes. Every change is checked against {@link #ALLOWED}, written to
 * claim_status_history and published as CLAIM_STATUS_CHANGED (after the transaction commits).
 *
 * <pre>
 * SUBMITTED → DOCS_PENDING → DOCS_VERIFIED → ELIGIBILITY_CHECKED → ASSESSED → UNDER_REVIEW
 *                                 (a failed check skips its step, never the review)
 * ELIGIBILITY_CHECKED | ASSESSED | UNDER_REVIEW → APPROVED | REJECTED          (Approval)
 * APPROVED | SETTLEMENT_FAILED → SETTLING → SETTLED | SETTLEMENT_FAILED         (3PC)
 * APPROVED → SETTLEMENT_FAILED                       (abort before this participant prepared)
 * </pre>
 * Must be called inside a transaction.
 */
@Component
public class ClaimStateMachine {

    static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = new EnumMap<>(ClaimStatus.class);

    static {
        ALLOWED.put(SUBMITTED, EnumSet.of(DOCS_PENDING));
        ALLOWED.put(DOCS_PENDING, EnumSet.of(DOCS_VERIFIED));
        ALLOWED.put(DOCS_VERIFIED, EnumSet.of(ELIGIBILITY_CHECKED, ASSESSED, UNDER_REVIEW));
        ALLOWED.put(ELIGIBILITY_CHECKED, EnumSet.of(ASSESSED, UNDER_REVIEW, APPROVED, REJECTED));
        ALLOWED.put(ASSESSED, EnumSet.of(UNDER_REVIEW, APPROVED, REJECTED));
        ALLOWED.put(UNDER_REVIEW, EnumSet.of(APPROVED, REJECTED));
        ALLOWED.put(APPROVED, EnumSet.of(SETTLING, SETTLEMENT_FAILED));
        ALLOWED.put(SETTLING, EnumSet.of(SETTLED, SETTLEMENT_FAILED));
        ALLOWED.put(SETTLEMENT_FAILED, EnumSet.of(SETTLING));
        ALLOWED.put(REJECTED, EnumSet.noneOf(ClaimStatus.class));
        ALLOWED.put(SETTLED, EnumSet.noneOf(ClaimStatus.class));
    }

    private final ClaimRepository claims;
    private final ClaimStatusHistoryRepository history;
    private final IntakeEvents events;
    private final Clock clock;

    public ClaimStateMachine(ClaimRepository claims, ClaimStatusHistoryRepository history, IntakeEvents events, Clock clock) {
        this.claims = claims;
        this.history = history;
        this.events = events;
        this.clock = clock;
    }

    public boolean isAllowed(ClaimStatus from, ClaimStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Moves the claim to {@code to}. Moving to the status it already has is a no-op (returns false), so
     * retried calls are safe.
     *
     * @throws ApiException 409 for a move the workflow does not allow
     */
    public boolean transition(Claim claim, ClaimStatus to, Actor actor, String comment) {
        ClaimStatus from = claim.getStatus();
        if (from == to) {
            return false;
        }
        if (!isAllowed(from, to)) {
            throw new ApiException(HttpStatus.CONFLICT, "A claim cannot move from " + from + " to " + to + ".");
        }
        Instant now = clock.instant();
        claim.changeStatus(to, now);
        claims.save(claim);
        history.save(new ClaimStatusHistory(claim.getId(), from, to, actor, comment, now));
        events.statusChanged(claim, from, to, actor, comment, now);
        return true;
    }

    /** The first history entry of a new claim (null → SUBMITTED). */
    public void initial(Claim claim, Actor actor) {
        history.save(new ClaimStatusHistory(claim.getId(), null, claim.getStatus(), actor, null, claim.getCreatedAt()));
    }
}
