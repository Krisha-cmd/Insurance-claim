package com.claims.approval.service;

import com.claims.approval.api.CurrentUser;
import com.claims.approval.api.dto.DecisionRequest;
import com.claims.approval.api.dto.DecisionResponse;
import com.claims.approval.api.dto.SettlementView;
import com.claims.approval.domain.ApprovalDecision;
import com.claims.approval.domain.Decision;
import com.claims.approval.domain.SettlementTx;
import com.claims.approval.events.AuditPublisher;
import com.claims.approval.events.Events;
import com.claims.approval.grpc.AssessmentClient;
import com.claims.approval.grpc.ClaimClient;
import com.claims.approval.repository.ApprovalDecisionRepository;
import com.claims.approval.repository.SettlementTxRepository;
import com.claims.common.error.ApiException;
import com.claims.proto.claim.v1.ClaimStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    static final String ALREADY_DECIDED = "A decision has already been recorded for this claim.";

    private final ApprovalDecisionRepository decisions;
    private final SettlementTxRepository txs;
    private final ThreePhaseCommitCoordinator coordinator;
    private final AssessmentClient assessment;
    private final ClaimClient claims;
    private final AuditPublisher audit;
    private final SettlementViewMapper views;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ApprovalService(ApprovalDecisionRepository decisions, SettlementTxRepository txs,
                           ThreePhaseCommitCoordinator coordinator, AssessmentClient assessment, ClaimClient claims,
                           AuditPublisher audit, SettlementViewMapper views, TransactionTemplate tx, Clock clock) {
        this.decisions = decisions;
        this.txs = txs;
        this.coordinator = coordinator;
        this.assessment = assessment;
        this.claims = claims;
        this.audit = audit;
        this.views = views;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * REJECTED: record it and set the claim to REJECTED.
     * APPROVED: read the final amount from Assessment, record it, set the claim to APPROVED, then start the
     * settlement in the background (the response carries its txId).
     *
     * <p>The decision row and the status change succeed or fail together: the status call runs inside the
     * DB transaction, so if Claim Intake refuses or is down nothing is recorded and the adjuster can retry.
     */
    public DecisionResponse decide(UUID claimId, DecisionRequest request, CurrentUser user) {
        if (decisions.findByClaimId(claimId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, ALREADY_DECIDED);
        }
        Decision decision = request.decision();
        String comment = request.comment().trim();
        BigDecimal amount = decision == Decision.APPROVED ? assessment.getFinalAmount(claimId) : null;
        ClaimStatus target = decision == Decision.APPROVED ? ClaimStatus.APPROVED : ClaimStatus.REJECTED;
        Instant now = clock.instant();

        ClaimStatus previous;
        try {
            previous = tx.execute(status -> {
                decisions.saveAndFlush(new ApprovalDecision(claimId, decision, amount, comment, user.id(), now));
                return claims.updateStatus(claimId, target, comment, user.actor(), "decision:" + claimId);
            });
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, ALREADY_DECIDED); // a concurrent decision won
        }

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("decision", decision.name());
        if (amount != null) change.put("approvedAmount", amount);
        audit.publish(Events.claim(decision == Decision.APPROVED ? "CLAIM_APPROVED" : "CLAIM_REJECTED", claimId,
                user.actor(), statusName(previous), target.name(), change, comment, null, now));
        log.info("Claim {} {} by {}{}", claimId, decision, user.id(), amount != null ? " for " + amount : "");

        if (decision == Decision.REJECTED) {
            return new DecisionResponse(claimId, decision, target.name(), null, null);
        }
        UUID txId = coordinator.start(claimId, amount);
        return new DecisionResponse(claimId, decision, target.name(), txId, 1);
    }

    /** The latest settlement attempt for the claim, if any. */
    public Optional<SettlementView> view(UUID claimId) {
        return txs.findFirstByClaimIdOrderByAttemptDesc(claimId)
                .map(t -> views.toView(t, coordinator.rowsOf(t.getTxId())));
    }

    /**
     * Starts a new settlement (attempt + 1) for an approved claim whose last settlement ABORTED.
     * Also allowed when an approved claim has no settlement at all (it failed to start).
     */
    public DecisionResponse retry(UUID claimId, CurrentUser user) {
        ApprovalDecision decision = decisions.findByClaimId(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No decision has been recorded for this claim."));
        if (decision.getDecision() != Decision.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Only approved claims can be settled.");
        }
        Optional<SettlementTx> last = txs.findFirstByClaimIdOrderByAttemptDesc(claimId);
        if (last.isPresent()) {
            switch (last.get().getState()) {
                case ABORTED -> { }
                case COMMITTED -> throw new ApiException(HttpStatus.CONFLICT, "This claim has already been settled.");
                default -> throw new ApiException(HttpStatus.CONFLICT, "A settlement is already running for this claim.");
            }
        }
        int attempt = last.map(t -> t.getAttempt() + 1).orElse(1);
        UUID txId = coordinator.start(claimId, decision.getAmount());

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("attempt", attempt);
        last.ifPresent(t -> change.put("previousTxId", t.getTxId()));
        audit.publish(Events.claim("SETTLEMENT_RETRIED", claimId, user.actor(), null, null, change, null, txId, clock.instant()));
        log.info("Settlement retried for claim {} by {} (attempt {})", claimId, user.id(), attempt);
        return new DecisionResponse(claimId, Decision.APPROVED, ClaimStatus.APPROVED.name(), txId, attempt);
    }

    private static String statusName(ClaimStatus s) {
        return s == null || s == ClaimStatus.CLAIM_STATUS_UNSPECIFIED || s == ClaimStatus.UNRECOGNIZED ? null : s.name();
    }
}
