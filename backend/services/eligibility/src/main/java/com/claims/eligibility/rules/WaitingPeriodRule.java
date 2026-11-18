package com.claims.eligibility.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** admission date − policy start ≥ the treatment's waiting period (in days). */
@Component
@Order(50)
public class WaitingPeriodRule implements EligibilityRule {

    @Override public String name() { return "WAITING_PERIOD"; }
    @Override public String label() { return "Waiting period completed"; }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        var rule = ctx.coverage().ruleFor(ctx.claim().treatmentCategory());
        if (rule.isEmpty()) return fail("Unknown treatment, so its waiting period is unknown");
        int waiting = rule.get().waitingPeriodDays();
        LocalDate admitted = ctx.claim().admissionDate(), start = ctx.policy().startDate();
        if (admitted == null) return fail("The claim has no admission date");
        if (start == null) return fail("The policy start date is unknown");
        long elapsed = ChronoUnit.DAYS.between(start, admitted);
        String msg = waiting + "-day waiting period from " + start + "; admitted after " + elapsed + " days";
        return elapsed >= waiting ? pass(msg) : fail(msg);
    }
}
