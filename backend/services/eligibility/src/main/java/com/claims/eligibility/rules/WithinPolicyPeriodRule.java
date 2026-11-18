package com.claims.eligibility.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** The admission date lies within the policy period, both ends included. */
@Component
@Order(20)
public class WithinPolicyPeriodRule implements EligibilityRule {

    @Override public String name() { return "WITHIN_POLICY_PERIOD"; }
    @Override public String label() { return "Admission date within the policy period"; }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        LocalDate admitted = ctx.claim().admissionDate();
        LocalDate from = ctx.policy().startDate(), to = ctx.policy().endDate();
        if (admitted == null) return fail("The claim has no admission date");
        if (from == null || to == null) return fail("The policy period is unknown");
        String msg = "Admitted " + admitted + "; policy valid " + from + " to " + to;
        return !admitted.isBefore(from) && !admitted.isAfter(to) ? pass(msg) : fail(msg);
    }
}
