package com.claims.intake.service;

import com.claims.intake.domain.Claim;
import com.claims.intake.domain.ClaimStatus;
import com.claims.intake.domain.ClaimStatusHistory;
import com.claims.intake.repository.ClaimStatusHistoryRepository;
import com.claims.intake.support.IntakeTestBase;
import com.claims.intake.support.IntakeTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The orchestrator when a check says no, fails, or is slow. */
class WorkflowIntegrationTest extends IntakeTestBase {

    @Autowired ClaimWorkflowOrchestrator orchestrator;
    @Autowired ClaimStatusHistoryRepository history;

    private Claim run(Claim c) {
        orchestrator.onDocumentsVerified(c.getId());
        await().atMost(Duration.ofSeconds(15)).until(() ->
                claims.findById(c.getId()).orElseThrow().getStatus() == ClaimStatus.UNDER_REVIEW && !orchestrator.isRunning(c.getId()));
        return claims.findById(c.getId()).orElseThrow();
    }

    private java.util.List<ClaimStatus> path(UUID claimId) {
        return history.findByClaimIdOrderByIdAsc(claimId).stream().map(ClaimStatusHistory::getToStatus).toList();
    }

    @Test
    void anIneligibleClaimStillReachesReview() {
        IntakeTestConfig.ELIGIBILITY.eligible = false;
        Claim c = run(claimIn(ClaimStatus.DOCS_VERIFIED, "5000"));

        assertThat(c.getEligible()).isFalse();
        assertThat(path(c.getId())).endsWith(ClaimStatus.ELIGIBILITY_CHECKED, ClaimStatus.ASSESSED, ClaimStatus.UNDER_REVIEW);
        assertThat(history.findByClaimIdOrderByIdAsc(c.getId()).get(3).getComment()).isEqualTo("Not eligible (failed: WAITING_PERIOD)");
    }

    @Test
    void eligibilityDown_skipsItsStep_assessmentStillRecorded() {
        IntakeTestConfig.ELIGIBILITY.unavailable = true;
        Claim c = run(claimIn(ClaimStatus.DOCS_VERIFIED, "5000"));

        assertThat(c.getEligible()).isNull();
        assertThat(c.getProposedAmount()).isEqualByComparingTo("67500.00");
        assertThat(path(c.getId())).endsWith(ClaimStatus.DOCS_VERIFIED, ClaimStatus.ASSESSED, ClaimStatus.UNDER_REVIEW);
        assertThat(events().typesFor(c.getId())).contains("CHECK_FAILED");
        assertThat(history.findByClaimIdOrderByIdAsc(c.getId()).getLast().getComment())
                .contains("Eligibility check failed: UNAVAILABLE");
    }

    @Test
    void aSlowCheckTimesOutAfterItsDeadline() {
        IntakeTestConfig.ASSESSMENT.delayMs = 3000; // deadline is 2 s in tests
        long start = System.nanoTime();
        Claim c = run(claimIn(ClaimStatus.DOCS_VERIFIED, "5000"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(c.getProposedAmount()).isNull();
        assertThat(c.getEligible()).isTrue();
        assertThat(path(c.getId())).endsWith(ClaimStatus.ELIGIBILITY_CHECKED, ClaimStatus.UNDER_REVIEW);
        assertThat(history.findByClaimIdOrderByIdAsc(c.getId()).getLast().getComment())
                .contains("Assessment failed: no reply within 2 s");
    }
}
