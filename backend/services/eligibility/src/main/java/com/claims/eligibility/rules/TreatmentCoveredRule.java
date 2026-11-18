package com.claims.eligibility.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** The plan has a rule for the claim's treatment category and that rule covers it. */
@Component
@Order(40)
public class TreatmentCoveredRule implements EligibilityRule {

    @Override public String name() { return "TREATMENT_COVERED"; }
    @Override public String label() { return "Treatment type is covered"; }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        String category = ctx.claim().treatmentCategory();
        return ctx.coverage().ruleFor(category)
                .map(r -> r.covered() ? pass(r.label() + " is covered") : fail(r.label() + " is not covered by the plan"))
                .orElseGet(() -> fail("The plan has no rule for '" + (category == null ? "" : category.trim()) + "'"));
    }
}
