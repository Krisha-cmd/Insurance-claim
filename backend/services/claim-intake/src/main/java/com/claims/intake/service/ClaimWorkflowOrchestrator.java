package com.claims.intake.service;

import com.claims.intake.config.IntakeProperties;
import com.claims.intake.domain.Actor;
import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStateMachine;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.grpc.AssessmentClient;
import com.claims.intake.grpc.EligibilityClient;
import com.claims.intake.mapper.ClaimMapper;
import com.claims.intake.repository.ClaimRepository;
import com.claims.proto.assessment.v1.AssessReply;
import com.claims.proto.claim.v1.ClaimDetails;
import com.claims.proto.claim.v1.RuleResult;
import com.claims.proto.eligibility.v1.CheckEligibilityReply;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * After all required documents are verified: runs Eligibility and Assessment in parallel (each with a 5 s
 * deadline), records both results, and moves the claim DOCS_VERIFIED → ELIGIBILITY_CHECKED → ASSESSED →
 * UNDER_REVIEW. A failed or timed-out check skips its step but the claim still reaches UNDER_REVIEW, where
 * the adjuster can override eligibility or adjust the amount.
 */
@Service
public class ClaimWorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClaimWorkflowOrchestrator.class);
    static final Actor ELIGIBILITY = Actor.system("eligibility");
    static final Actor ASSESSMENT = Actor.system("assessment");

    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final EligibilityClient eligibility;
    private final AssessmentClient assessment;
    private final ClaimStateMachine stateMachine;
    private final ClaimMapper mapper;
    private final IntakeEvents events;
    private final TransactionTemplate tx;
    private final TaskExecutor workflowExecutor;
    private final TaskExecutor checkExecutor;
    private final Clock clock;
    private final Duration checkTimeout;
    private final Duration stuckAfter;

    /** Claims whose workflow is running in this process. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public ClaimWorkflowOrchestrator(ClaimRepository claims, ClaimService claimService, EligibilityClient eligibility,
                                     AssessmentClient assessment, ClaimStateMachine stateMachine, ClaimMapper mapper,
                                     IntakeEvents events, TransactionTemplate tx,
                                     @Qualifier("workflowExecutor") TaskExecutor workflowExecutor,
                                     @Qualifier("checkExecutor") TaskExecutor checkExecutor,
                                     Clock clock, IntakeProperties props) {
        this.claims = claims;
        this.claimService = claimService;
        this.eligibility = eligibility;
        this.assessment = assessment;
        this.stateMachine = stateMachine;
        this.mapper = mapper;
        this.events = events;
        this.tx = tx;
        this.workflowExecutor = workflowExecutor;
        this.checkExecutor = checkExecutor;
        this.clock = clock;
        this.checkTimeout = props.workflow().checkTimeout();
        this.stuckAfter = props.workflow().stuckAfter();
    }

    /** Starts the checks in the background (no-op if they are already running for this claim). */
    public void onDocumentsVerified(UUID claimId) {
        if (!running.add(claimId)) {
            return;
        }
        workflowExecutor.execute(() -> {
            try {
                claims.findById(claimId)
                        .filter(c -> c.getStatus() == ClaimStatus.DOCS_VERIFIED)
                        .ifPresent(this::runChecksInParallel);
            } catch (RuntimeException e) {
                log.error("Workflow for claim {} failed; the sweep will retry it", claimId, e);
            } finally {
                running.remove(claimId);
            }
        });
    }

    /** Restarts claims left in DOCS_VERIFIED, e.g. by a restart between the commit and the checks. */
    @Scheduled(initialDelayString = "${claims.intake.workflow.sweep-interval}",
               fixedDelayString = "${claims.intake.workflow.sweep-interval}")
    public void resumeStuck() {
        for (Claim c : claims.findByStatusAndUpdatedAtLessThanEqual(ClaimStatus.DOCS_VERIFIED, clock.instant().minus(stuckAfter))) {
            log.info("Claim {} is still DOCS_VERIFIED; starting its checks again", c.getId());
            onDocumentsVerified(c.getId());
        }
    }

    public boolean isRunning(UUID claimId) {
        return running.contains(claimId);
    }

    private record Result<T>(T value, String error) {
        boolean ok() { return error == null; }
    }

    private void runChecksInParallel(Claim claim) {
        ClaimDetails details = mapper.details(claim);
        CompletableFuture<CheckEligibilityReply> el =
                CompletableFuture.supplyAsync(() -> eligibility.check(details), checkExecutor);
        CompletableFuture<AssessReply> as =
                CompletableFuture.supplyAsync(() -> assessment.assess(details), checkExecutor);

        Result<CheckEligibilityReply> elResult = await(el, "eligibility", claim.getId());
        Result<AssessReply> asResult = await(as, "assessment", claim.getId());

        tx.executeWithoutResult(status -> {
            Claim c = claims.findById(claim.getId()).orElseThrow();
            if (c.getStatus() != ClaimStatus.DOCS_VERIFIED) {
                return; // someone else finished it
            }
            List<String> summary = new ArrayList<>();

            if (elResult.ok()) {
                CheckEligibilityReply r = elResult.value();
                List<String> failed = r.getRulesList().stream().filter(x -> !x.getPassed()).map(RuleResult::getRule).toList();
                claimService.recordEligibility(c, r.getEligible(), mapper.rulesJson(r.getRulesList()), false, null, ELIGIBILITY);
                events.eligibilityChecked(c, r.getEligible(), failed, clock.instant());
                String note = r.getEligible() ? "Eligible" : "Not eligible (failed: " + String.join(", ", failed) + ")";
                stateMachine.transition(c, ClaimStatus.ELIGIBILITY_CHECKED, ELIGIBILITY, note);
                summary.add(note);
            } else {
                events.checkFailed(c, "eligibility", elResult.error(), clock.instant());
                summary.add("Eligibility check failed: " + elResult.error());
            }

            BigDecimal proposed = asResult.ok() ? parseAmount(asResult.value().getProposedAmount()) : null;
            if (proposed != null) {
                claimService.recordAssessment(c, proposed);
                events.assessed(c, c.getProposedAmount(), clock.instant());
                String note = "Proposed amount " + c.getProposedAmount().toPlainString();
                stateMachine.transition(c, ClaimStatus.ASSESSED, ASSESSMENT, note);
                summary.add(note);
            } else {
                String error = asResult.ok() ? "invalid amount '" + asResult.value().getProposedAmount() + "'" : asResult.error();
                events.checkFailed(c, "assessment", error, clock.instant());
                summary.add("Assessment failed: " + error);
            }

            stateMachine.transition(c, ClaimStatus.UNDER_REVIEW, Actor.intake(), String.join("; ", summary));
            log.info("Claim {} is UNDER_REVIEW ({})", c.getId(), String.join("; ", summary));
        });
    }

    private <T> Result<T> await(CompletableFuture<T> f, String check, UUID claimId) {
        try {
            // The gRPC deadline fires first; this is only a safety net.
            return new Result<>(f.get(checkTimeout.toMillis() + 2000, TimeUnit.MILLISECONDS), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result<>(null, "interrupted");
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException || e instanceof CompletionException
                    ? e.getCause() : e;
            String error = cause instanceof StatusRuntimeException sre
                    ? (sre.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED
                        ? "no reply within " + checkTimeout.toSeconds() + " s" : sre.getStatus().getCode().name())
                    : cause instanceof java.util.concurrent.TimeoutException ? "no reply within " + checkTimeout.toSeconds() + " s"
                    : String.valueOf(cause.getMessage());
            log.warn("Claim {}: {} check failed: {}", claimId, check, error);
            return new Result<>(null, error);
        }
    }

    private static BigDecimal parseAmount(String s) {
        try {
            BigDecimal v = new BigDecimal(s);
            return v.signum() >= 0 ? v : null;
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
