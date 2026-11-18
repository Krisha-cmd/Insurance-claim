package com.claims.intake.service;

import com.claims.common.event.ClaimEvent;
import com.claims.common.event.EventPublisher;
import com.claims.intake.domain.Actor;
import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimDocument;
import com.claims.intake.domain.ClaimStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Builds and publishes this service's events. Inside a transaction an event is sent only after the commit,
 * so a rolled-back change never shows up in the audit trail.
 *
 * <p>Claim Intake owns the claim status, so it is the only service whose events carry {@code toStatus}
 * (CLAIM_CREATED and CLAIM_STATUS_CHANGED), which is what the Audit Flink job counts per status.
 */
@Component
public class IntakeEvents {

    private final EventPublisher publisher;

    public IntakeEvents(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public void claimCreated(Claim c, Actor actor) {
        publish(ClaimEvent.builder("CLAIM_CREATED", Actor.SERVICE, c.getId(), c.getCreatedAt())
                .actor(actor.toEvent()).policyNo(c.getPolicyNo()).amount(c.getClaimedAmount())
                .status(null, c.getStatus().name())
                .detail("claimNo", c.getClaimNo())
                .detail("hospital", c.getHospitalName())
                .detail("treatmentType", c.getTreatmentCategory())
                .build());
    }

    public void statusChanged(Claim c, ClaimStatus from, ClaimStatus to, Actor actor, String comment, Instant at) {
        publish(ClaimEvent.builder("CLAIM_STATUS_CHANGED", Actor.SERVICE, c.getId(), at)
                .actor(actor.toEvent()).policyNo(c.getPolicyNo()).status(from.name(), to.name()).comment(comment)
                .build());
    }

    public void documentResult(ClaimDocument d, String policyNo, Instant at) {
        boolean ok = d.getVerificationStatus() == com.claims.intake.domain.VerificationStatus.VERIFIED;
        publish(ClaimEvent.builder(ok ? "DOCUMENT_VERIFIED" : "DOCUMENT_REJECTED", Actor.SERVICE, d.getClaimId(), at)
                .actor(Actor.system("document-verification").toEvent()).policyNo(policyNo)
                .detail("documentId", d.getId())
                .detail("docType", d.getDocType().name())
                .detail("fileName", d.getFileName())
                .detail("reason", d.getRejectionReason())
                .build());
    }

    public void eligibilityChecked(Claim c, boolean eligible, Object failedRules, Instant at) {
        publish(ClaimEvent.builder("ELIGIBILITY_CHECKED", Actor.SERVICE, c.getId(), at)
                .actor(Actor.system("eligibility").toEvent()).policyNo(c.getPolicyNo())
                .detail("eligible", eligible).detail("failedRules", failedRules)
                .build());
    }

    public void assessed(Claim c, BigDecimal proposed, Instant at) {
        publish(ClaimEvent.builder("ASSESSMENT_COMPLETED", Actor.SERVICE, c.getId(), at)
                .actor(Actor.system("assessment").toEvent()).policyNo(c.getPolicyNo()).amount(proposed)
                .detail("field", "proposedAmount").detail("after", proposed)
                .build());
    }

    public void checkFailed(Claim c, String check, String error, Instant at) {
        publish(ClaimEvent.builder("CHECK_FAILED", Actor.SERVICE, c.getId(), at).policyNo(c.getPolicyNo())
                .detail("check", check).detail("error", error).build());
    }

    /** This participant's view of a settlement tx (participant = claim-intake). */
    public void participant(String type, UUID claimId, UUID txId, String vote, String state, Map<String, Object> change, Instant at) {
        publish(ClaimEvent.builder(type, Actor.SERVICE, claimId, at).tx(txId, null)
                .participant(Actor.SERVICE, vote, state).change(change).build());
    }

    private void publish(ClaimEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publish(event);
                }
            });
        } else {
            publisher.publish(event);
        }
    }
}
