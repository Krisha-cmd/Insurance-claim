package com.claims.eligibility.service;

import com.claims.eligibility.rules.EligibilityRule;
import com.claims.eligibility.rules.RuleContext;
import com.claims.eligibility.rules.RuleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs every {@link EligibilityRule} bean, in {@code @Order}, and collects all results: it never stops at the
 * first failure, so the adjuster sees every reason at once. Eligible means every rule passed.
 */
@Component
public class EligibilityEngine {

    private static final Logger log = LoggerFactory.getLogger(EligibilityEngine.class);

    private final List<EligibilityRule> rules;

    /** Spring injects the rules sorted by {@code @Order}. */
    public EligibilityEngine(List<EligibilityRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<RuleResult> run(RuleContext ctx) {
        List<RuleResult> results = new ArrayList<>(rules.size());
        for (EligibilityRule rule : rules) {
            RuleResult r;
            try {
                r = rule.evaluate(ctx);
                if (r == null) r = new RuleResult(rule.name(), rule.label(), false, "The rule returned no result");
            } catch (RuntimeException e) {
                // A broken rule must not hide the others, and must never make a claim eligible.
                log.warn("Rule {} failed for claim {}", rule.name(), ctx.claim().claimId(), e);
                r = new RuleResult(rule.name(), rule.label(), false, "Could not be evaluated: " + e.getMessage());
            }
            results.add(r);
        }
        return List.copyOf(results);
    }

    public static boolean eligible(List<RuleResult> results) {
        return !results.isEmpty() && results.stream().allMatch(RuleResult::passed);
    }

    public List<EligibilityRule> rules() {
        return rules;
    }
}
