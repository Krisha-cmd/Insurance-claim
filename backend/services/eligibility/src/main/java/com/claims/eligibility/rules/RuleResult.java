package com.claims.eligibility.rules;

/** The outcome of one rule. {@code message} explains it, e.g. "Policy POL-2026-00018 is LAPSED". */
public record RuleResult(String rule, String label, boolean passed, String message) {
}
