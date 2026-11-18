package com.claims.eligibility.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the rules may look at, gathered before any rule runs: the claim (Claim Intake), and the policy, its
 * plan's coverage rules and its remaining balance (Policy). Rules do no I/O.
 */
public record RuleContext(ClaimFacts claim, PolicyFacts policy, Coverage coverage, Balance balance) {

    /** From ClaimDetails. {@code admissionDate} is null if Claim Intake sent none or an unreadable one. */
    public record ClaimFacts(UUID claimId, String policyNo, UUID memberId, LocalDate admissionDate,
                             String treatmentCategory, BigDecimal claimedAmount) {
    }

    /** From Policy's GetPolicy. {@code members}: member id → name. */
    public record PolicyFacts(String policyNo, String status, LocalDate startDate, LocalDate endDate,
                              Map<UUID, String> members) {
        public PolicyFacts {
            members = members == null ? Map.of() : Map.copyOf(members);
        }
    }

    /** From Policy's GetCoverage. */
    public record Coverage(List<Rule> rules) {
        public Coverage {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }

        /** The plan rule for a treatment category, matched by code (DAYCARE) or label (Day care procedure), ignoring case. */
        public Optional<Rule> ruleFor(String treatmentCategory) {
            if (treatmentCategory == null || treatmentCategory.isBlank()) return Optional.empty();
            String t = treatmentCategory.trim();
            return rules.stream().filter(r -> t.equalsIgnoreCase(r.category()) || t.equalsIgnoreCase(r.label())).findFirst();
        }
    }

    public record Rule(String category, String label, boolean covered, int waitingPeriodDays) {
    }

    /** From Policy's GetBalance: sum insured − utilised − reserved. */
    public record Balance(BigDecimal remaining) {
    }
}
