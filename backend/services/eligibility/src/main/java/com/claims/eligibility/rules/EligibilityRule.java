package com.claims.eligibility.rules;

/**
 * One eligibility check. Implementations are Spring beans ordered with {@code @Order}; the engine runs every
 * bean of this type, so a new rule needs no change anywhere else.
 */
public interface EligibilityRule {

    /** Stable code, e.g. POLICY_ACTIVE (stored by Claim Intake, shown in the audit trail). */
    String name();

    /** Human-readable, e.g. "Policy is active". */
    String label();

    /** Must not throw for bad data: fail with a message instead. (The engine still guards against it.) */
    RuleResult evaluate(RuleContext ctx);

    default RuleResult pass(String message) {
        return new RuleResult(name(), label(), true, message);
    }

    default RuleResult fail(String message) {
        return new RuleResult(name(), label(), false, message);
    }
}
