package com.claims.eligibility.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The policy's status is ACTIVE (not LAPSED). */
@Component
@Order(10)
public class PolicyActiveRule implements EligibilityRule {

    @Override public String name() { return "POLICY_ACTIVE"; }
    @Override public String label() { return "Policy is active"; }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        String status = ctx.policy().status();
        String msg = "Policy " + ctx.policy().policyNo() + " is " + (status == null || status.isBlank() ? "of unknown status" : status);
        return "ACTIVE".equalsIgnoreCase(status) ? pass(msg) : fail(msg);
    }
}
