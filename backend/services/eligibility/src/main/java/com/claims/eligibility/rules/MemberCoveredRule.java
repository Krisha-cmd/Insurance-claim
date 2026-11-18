package com.claims.eligibility.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** The patient (the claim's member) is insured under the policy. */
@Component
@Order(30)
public class MemberCoveredRule implements EligibilityRule {

    @Override public String name() { return "MEMBER_COVERED"; }
    @Override public String label() { return "Patient is a member of the policy"; }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        UUID member = ctx.claim().memberId();
        if (member == null) return fail("The claim names no patient");
        String name = ctx.policy().members().get(member);
        return name != null
                ? pass(name + " is insured under " + ctx.policy().policyNo())
                : fail("The patient is not insured under " + ctx.policy().policyNo());
    }
}
