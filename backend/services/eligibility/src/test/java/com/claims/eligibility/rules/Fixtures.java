package com.claims.eligibility.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rule contexts that mirror Policy's seed (V2__seed.sql): POL-2026-00017 is ACTIVE for 2026 with Asha (SELF) and
 * Vikram (SPOUSE); POL-2026-00018 is LAPSED and ran through 2025, Asha only. The plan "Family Health Plus" covers
 * everything but cosmetic treatment; maternity has a 270-day waiting period.
 */
public final class Fixtures {

    public static final UUID ASHA_00017 = UUID.fromString("567dacf4-a6e5-461f-b094-8ded155a5a7e");
    public static final UUID VIKRAM_00017 = UUID.fromString("7971f862-38fe-4a72-9193-f8d5a7bc591b");
    public static final UUID ASHA_00018 = UUID.fromString("6ee0f2b2-1c47-43a7-b6f6-3af17479a96f");

    public static final RuleContext.Coverage PLAN = new RuleContext.Coverage(List.of(
            new RuleContext.Rule("HOSPITALISATION", "Hospitalisation", true, 0),
            new RuleContext.Rule("SURGERY", "Surgery", true, 0),
            new RuleContext.Rule("MATERNITY", "Maternity", true, 270),
            new RuleContext.Rule("DAYCARE", "Day care procedure", true, 0),
            new RuleContext.Rule("COSMETIC", "Cosmetic treatment", false, 0)));

    public static final RuleContext.PolicyFacts POLICY_00017 = new RuleContext.PolicyFacts("POL-2026-00017", "ACTIVE",
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"),
            Map.of(ASHA_00017, "Asha Verma", VIKRAM_00017, "Vikram Verma"));

    public static final RuleContext.PolicyFacts POLICY_00018 = new RuleContext.PolicyFacts("POL-2026-00018", "LAPSED",
            LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"), Map.of(ASHA_00018, "Asha Verma"));

    private Fixtures() {
    }

    /** A hospitalisation claim for Asha on POL-2026-00017, admitted 2026-03-10, 500000 left: passes every rule. */
    public static RuleContext eligible() {
        return new RuleContext(claim(ASHA_00017, "2026-03-10", "Hospitalisation", "POL-2026-00017"), POLICY_00017, PLAN,
                new RuleContext.Balance(new BigDecimal("500000.00")));
    }

    public static RuleContext.ClaimFacts claim(UUID member, String admitted, String category, String policyNo) {
        return new RuleContext.ClaimFacts(UUID.randomUUID(), policyNo, member,
                admitted == null || admitted.isBlank() ? null : LocalDate.parse(admitted), category, new BigDecimal("67500.00"));
    }

    public static RuleContext withClaim(RuleContext c, RuleContext.ClaimFacts claim) {
        return new RuleContext(claim, c.policy(), c.coverage(), c.balance());
    }

    public static RuleContext withPolicy(RuleContext c, RuleContext.PolicyFacts policy) {
        return new RuleContext(c.claim(), policy, c.coverage(), c.balance());
    }

    public static RuleContext withBalance(RuleContext c, String remaining) {
        return new RuleContext(c.claim(), c.policy(), c.coverage(),
                new RuleContext.Balance(remaining == null || remaining.isBlank() ? null : new BigDecimal(remaining)));
    }
}
