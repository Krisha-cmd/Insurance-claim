package com.claims.assessment.service;

import com.claims.assessment.api.CurrentUser;
import com.claims.assessment.api.dto.AdjustRequest;
import com.claims.assessment.config.AssessmentProperties;
import com.claims.assessment.domain.Assessment;
import com.claims.assessment.grpc.ClaimClient;
import com.claims.assessment.grpc.PolicyClient;
import com.claims.assessment.repository.AssessmentRepository;
import com.claims.common.error.ApiException;
import com.claims.common.event.ClaimEvent;
import com.claims.common.event.EventPublisher;
import com.claims.proto.claim.v1.Actor;
import com.claims.proto.claim.v1.ClaimStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);
    private static final String SERVICE = "assessment";
    private static final int MAX_ATTEMPTS = 3;

    /** Claim Intake's ClaimService.REVIEWABLE: the amount may change only until a decision is made. */
    static final Set<ClaimStatus> REVIEWABLE =
            Set.of(ClaimStatus.ELIGIBILITY_CHECKED, ClaimStatus.ASSESSED, ClaimStatus.UNDER_REVIEW);

    private final AssessmentRepository assessments;
    private final AssessmentCalculator calculator;
    private final PolicyClient policy;
    private final ClaimClient claims;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String currency;
    private final Timer calculateTimer;

    public AssessmentService(AssessmentRepository assessments, AssessmentCalculator calculator, PolicyClient policy,
                             ClaimClient claims, EventPublisher events, TransactionTemplate tx, Clock clock,
                             AssessmentProperties props, MeterRegistry meters) {
        this.assessments = assessments;
        this.calculator = calculator;
        this.policy = policy;
        this.claims = claims;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.currency = props.currency();
        this.calculateTimer = Timer.builder("claims.assessment.calculate")
                .description("Time spent in the payout calculation itself (CPU only, no I/O)")
                .register(meters);
    }

    /**
     * GRPC Assess, called by Claim Intake. Idempotent: one row per claim. A repeated call recalculates (the
     * balance may have changed) unless an adjuster has adjusted the amount, which is then kept.
     */
    public Assessment assess(Assessment.ClaimFacts claim, BigDecimal claimed) {
        if (claimed == null || claimed.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The claimed amount must be zero or more.");
        }
        var existing = assessments.findByClaimId(claim.claimId());
        if (existing.isPresent() && existing.get().isAdjusted()) {
            return existing.get();
        }

        // I/O first, so the timer below measures only the calculation.
        Coverage coverage = policy.coverage(claim.policyNo());
        Balance balance = policy.balance(claim.policyNo());
        Calculation calc = calculateTimer.record(
                () -> calculator.calculate(claimed, coverage, balance, claim.treatmentCategory()));

        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> {
                    Assessment a = assessments.findByClaimId(claim.claimId()).orElse(null);
                    if (a == null) {
                        a = Assessment.create(claim, calc, currency, clock.instant());
                    } else if (a.isAdjusted()) {
                        return a;
                    } else {
                        a.recalculate(claim, calc, clock.instant());
                    }
                    return assessments.saveAndFlush(a);
                });
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException race) {
                // A concurrent Assess for the same claim won; apply ours on top of its row.
                if (attempt >= MAX_ATTEMPTS) throw race;
                log.info("Concurrent assessment of claim {}, retrying ({})", claim.claimId(), attempt);
            }
        }
    }

    /** REST GET: the owner of the claim or any adjuster. */
    public Assessment get(UUID claimId, CurrentUser user) {
        Assessment a = assessments.findByClaimId(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "The claim has not been assessed yet."));
        if (!user.isAdjuster() && !a.isOwnedBy(user.id(), user.policyholderId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only see the assessment of your own claims.");
        }
        return a;
    }

    /**
     * REST PUT .../adjust (adjusters). The adjustment and Claim Intake's RecordAssessment succeed or fail
     * together: the call runs inside the database transaction, so if Claim Intake refuses or is down, nothing
     * is saved. ASSESSMENT_ADJUSTED is published after the commit.
     */
    public Assessment adjust(UUID claimId, AdjustRequest req, CurrentUser user) {
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        String comment = req.comment().trim();
        if (comment.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "comment must be 10 to 2000 characters");
        }
        Assessment current = assessments.findByClaimId(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "The claim has not been assessed yet."));
        if (amount.compareTo(current.getClaimedAmount()) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The amount cannot exceed the claimed amount of "
                    + current.getClaimedAmount().toPlainString() + ".");
        }
        if (!REVIEWABLE.contains(claims.status(claimId))) {
            throw new ApiException(HttpStatus.CONFLICT, "The amount can only be changed before a decision is made.");
        }

        Actor actor = Actor.newBuilder().setId(user.id().toString()).setRole("ADJUSTER")
                .setName(user.name() == null ? "" : user.name()).build();
        BigDecimal[] before = new BigDecimal[1];
        Assessment saved;
        try {
            saved = tx.execute(status -> {
                Assessment a = assessments.findByClaimId(claimId).orElseThrow();
                before[0] = a.getFinalAmount();
                a.adjust(amount, comment, user.id(), user.name(), clock.instant());
                Assessment s = assessments.saveAndFlush(a);
                claims.recordAssessment(claimId, amount, comment, actor);
                return s;
            });
        } catch (OptimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, "The assessment was changed at the same time. Please reload and try again.");
        }

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before[0]);
        change.put("after", amount);
        events.publish(ClaimEvent.builder("ASSESSMENT_ADJUSTED", SERVICE, claimId, clock.instant())
                .policyNo(saved.getPolicyNo())
                .actor(new ClaimEvent.Actor(user.id().toString(), "ADJUSTER", user.name()))
                .amount(amount)
                .change(change)
                .comment(comment)
                .build());
        log.info("Claim {} assessment adjusted {} -> {} by {}", claimId, before[0], amount, user.id());
        return saved;
    }

    /** GRPC GetFinalAmount, for Approval: the assessment whose final amount is settled. */
    public Assessment forSettlement(UUID claimId) {
        return assessments.findByClaimId(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Claim " + claimId + " has not been assessed."));
    }

    /** The adjusted amount if there is one, else the proposed one. */
    public BigDecimal finalAmount(UUID claimId) {
        return forSettlement(claimId).getFinalAmount();
    }
}
