package com.claims.assessment.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * What the calculation needs from the policy's plan (Policy's {@code GetCoverage}).
 *
 * @param deductible deducted once per claim, after the sub-limit cap
 * @param copayPct   co-pay in percent, e.g. 10.00
 */
public record Coverage(BigDecimal deductible, BigDecimal copayPct, List<Rule> rules) {

    /**
     * @param category the rule's code, e.g. DAYCARE
     * @param label    the text a claim carries as its treatment type, e.g. "Day care procedure"
     * @param subLimit null when the category has no sub-limit
     */
    public record Rule(String category, String label, boolean covered, BigDecimal subLimit) {
    }

    public Coverage {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** The rule for a claim's treatment category, matched by code or label, ignoring case. */
    public Optional<Rule> ruleFor(String treatmentCategory) {
        if (treatmentCategory == null) return Optional.empty();
        String t = treatmentCategory.trim();
        return rules.stream()
                .filter(r -> t.equalsIgnoreCase(r.category()) || t.equalsIgnoreCase(r.label()))
                .findFirst();
    }
}
