package com.claims.eligibility.service;

import com.claims.eligibility.rules.BalanceAvailableRule;
import com.claims.eligibility.rules.EligibilityRule;
import com.claims.eligibility.rules.Fixtures;
import com.claims.eligibility.rules.MemberCoveredRule;
import com.claims.eligibility.rules.PolicyActiveRule;
import com.claims.eligibility.rules.RuleContext;
import com.claims.eligibility.rules.RuleResult;
import com.claims.eligibility.rules.TreatmentCoveredRule;
import com.claims.eligibility.rules.WaitingPeriodRule;
import com.claims.eligibility.rules.WithinPolicyPeriodRule;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static com.claims.eligibility.rules.Fixtures.ASHA_00018;
import static com.claims.eligibility.rules.Fixtures.PLAN;
import static com.claims.eligibility.rules.Fixtures.POLICY_00018;
import static org.assertj.core.api.Assertions.assertThat;

class EligibilityEngineTest {

    private static final List<String> ORDER = List.of("POLICY_ACTIVE", "WITHIN_POLICY_PERIOD", "MEMBER_COVERED",
            "TREATMENT_COVERED", "WAITING_PERIOD", "BALANCE_AVAILABLE");

    /** The six rules, shuffled, then sorted the way Spring sorts injected beans (by @Order). */
    private static List<EligibilityRule> sixRules(EligibilityRule... extra) {
        List<EligibilityRule> rules = new ArrayList<>(List.of(new BalanceAvailableRule(), new WaitingPeriodRule(),
                new PolicyActiveRule(), new TreatmentCoveredRule(), new MemberCoveredRule(), new WithinPolicyPeriodRule()));
        rules.addAll(List.of(extra));
        Collections.shuffle(rules, new Random(7));
        AnnotationAwareOrderComparator.sort(rules);
        return rules;
    }

    @Test
    void anExpiredPolicyFailsTwoRulesAndStillReportsAllSix() {
        // A claim on POL-2026-00018 (LAPSED, ran through 2025), for its member, admitted in 2026.
        RuleContext ctx = new RuleContext(Fixtures.claim(ASHA_00018, "2026-03-10", "Hospitalisation", "POL-2026-00018"),
                POLICY_00018, PLAN, new RuleContext.Balance(new BigDecimal("300000.00")));

        List<RuleResult> results = new EligibilityEngine(sixRules()).run(ctx);

        assertThat(results).extracting(RuleResult::rule).containsExactlyElementsOf(ORDER);
        assertThat(results).filteredOn(r -> !r.passed()).extracting(RuleResult::rule)
                .containsExactly("POLICY_ACTIVE", "WITHIN_POLICY_PERIOD");
        assertThat(results.get(0).message()).isEqualTo("Policy POL-2026-00018 is LAPSED");
        assertThat(results.get(1).message()).isEqualTo("Admitted 2026-03-10; policy valid 2025-01-01 to 2025-12-31");
        assertThat(EligibilityEngine.eligible(results)).isFalse();
    }

    @Test
    void allPassedMeansEligible() {
        List<RuleResult> results = new EligibilityEngine(sixRules()).run(Fixtures.eligible());

        assertThat(results).hasSize(6).allMatch(RuleResult::passed);
        assertThat(EligibilityEngine.eligible(results)).isTrue();
    }

    @Test
    void doesNotStopAtTheFirstFailure() {
        // Everything wrong at once: lapsed, outside the period, not a member, not covered, no balance left.
        RuleContext ctx = new RuleContext(Fixtures.claim(java.util.UUID.randomUUID(), "2026-03-10", "Cosmetic treatment",
                "POL-2026-00018"), POLICY_00018, PLAN, new RuleContext.Balance(BigDecimal.ZERO));

        List<RuleResult> results = new EligibilityEngine(sixRules()).run(ctx);

        assertThat(results).hasSize(6);
        assertThat(results).filteredOn(r -> !r.passed()).extracting(RuleResult::rule)
                .containsExactly("POLICY_ACTIVE", "WITHIN_POLICY_PERIOD", "MEMBER_COVERED", "TREATMENT_COVERED",
                        "BALANCE_AVAILABLE");
    }

    @Order(25)
    static class ExplodingRule implements EligibilityRule {
        @Override public String name() { return "EXPLODES"; }
        @Override public String label() { return "Always throws"; }
        @Override public RuleResult evaluate(RuleContext ctx) { throw new IllegalStateException("boom"); }
    }

    @Test
    void aRuleThatThrowsFailsOnItsOwnAndTheOthersStillRun() {
        List<RuleResult> results = new EligibilityEngine(sixRules(new ExplodingRule())).run(Fixtures.eligible());

        assertThat(results).hasSize(7);
        assertThat(results.get(2)).isEqualTo(new RuleResult("EXPLODES", "Always throws", false, "Could not be evaluated: boom"));
        assertThat(results).filteredOn(RuleResult::passed).hasSize(6);
        assertThat(EligibilityEngine.eligible(results)).as("a broken rule never makes a claim eligible").isFalse();
    }

    @Test
    void aNewRuleIsPickedUpInItsOrderWithoutChangingTheEngine() {
        @Order(55)
        class ClaimedAmountPositiveRule implements EligibilityRule {
            @Override public String name() { return "AMOUNT_POSITIVE"; }
            @Override public String label() { return "Claimed amount is positive"; }
            @Override public RuleResult evaluate(RuleContext ctx) {
                return ctx.claim().claimedAmount().signum() > 0 ? pass("ok") : fail("zero");
            }
        }

        List<RuleResult> results = new EligibilityEngine(sixRules(new ClaimedAmountPositiveRule())).run(Fixtures.eligible());

        assertThat(results).extracting(RuleResult::rule).containsExactly("POLICY_ACTIVE", "WITHIN_POLICY_PERIOD",
                "MEMBER_COVERED", "TREATMENT_COVERED", "WAITING_PERIOD", "AMOUNT_POSITIVE", "BALANCE_AVAILABLE");
    }

    @Test
    void noRulesIsNeverEligible() {
        assertThat(EligibilityEngine.eligible(List.of())).isFalse();
    }
}
