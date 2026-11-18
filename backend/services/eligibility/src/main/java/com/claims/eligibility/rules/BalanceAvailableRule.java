package com.claims.eligibility.rules;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Some sum insured is left (remaining balance &gt; 0). Whether it covers the full amount is Assessment's
 * business: it caps the payout at the remaining balance.
 */
@Component
@Order(60)
public class BalanceAvailableRule implements EligibilityRule {

    @Override public String name() { return "BALANCE_AVAILABLE"; }
    @Override public String label() { return "Sum insured remaining"; }

    @Override
    public RuleResult evaluate(RuleContext ctx) {
        BigDecimal remaining = ctx.balance().remaining();
        if (remaining == null) return fail("The remaining balance is unknown");
        String msg = "Remaining balance " + remaining.toPlainString();
        return remaining.signum() > 0 ? pass(msg) : fail(msg);
    }
}
