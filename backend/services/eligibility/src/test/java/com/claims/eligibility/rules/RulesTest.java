package com.claims.eligibility.rules;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.UUID;

import static com.claims.eligibility.rules.Fixtures.ASHA_00017;
import static com.claims.eligibility.rules.Fixtures.POLICY_00017;
import static com.claims.eligibility.rules.Fixtures.claim;
import static com.claims.eligibility.rules.Fixtures.eligible;
import static com.claims.eligibility.rules.Fixtures.withBalance;
import static com.claims.eligibility.rules.Fixtures.withClaim;
import static com.claims.eligibility.rules.Fixtures.withPolicy;
import static org.assertj.core.api.Assertions.assertThat;

/** One table per rule, each with passing and failing rows. Every other fact is the always-eligible fixture. */
class RulesTest {

    private static void check(EligibilityRule rule, RuleContext ctx, boolean expected, String messagePart) {
        RuleResult r = rule.evaluate(ctx);
        assertThat(r.rule()).isEqualTo(rule.name());
        assertThat(r.label()).isEqualTo(rule.label());
        assertThat(r.passed()).as(r.message()).isEqualTo(expected);
        assertThat(r.message()).contains(messagePart);
    }

    @Nested
    class PolicyActive {
        @ParameterizedTest(name = "status {0} → {1}")
        @CsvSource(delimiter = '|', textBlock = """
                ACTIVE  | true  | is ACTIVE
                active  | true  | is active
                LAPSED  | false | is LAPSED
                ''      | false | of unknown status
                """)
        void table(String status, boolean passed, String message) {
            var policy = new RuleContext.PolicyFacts("POL-2026-00017", status, POLICY_00017.startDate(),
                    POLICY_00017.endDate(), POLICY_00017.members());
            check(new PolicyActiveRule(), withPolicy(eligible(), policy), passed, message);
        }
    }

    @Nested
    class WithinPolicyPeriod {
        @ParameterizedTest(name = "admitted {0} → {1}")
        @CsvSource(delimiter = '|', textBlock = """
                2026-03-10 | true  | valid 2026-01-01 to 2026-12-31
                2026-01-01 | true  | Admitted 2026-01-01
                2026-12-31 | true  | Admitted 2026-12-31
                2025-12-31 | false | Admitted 2025-12-31
                2027-01-01 | false | Admitted 2027-01-01
                ''         | false | no admission date
                """)
        void table(String admitted, boolean passed, String message) {
            var ctx = withClaim(eligible(), claim(ASHA_00017, admitted, "Hospitalisation", "POL-2026-00017"));
            check(new WithinPolicyPeriodRule(), ctx, passed, message);
        }
    }

    @Nested
    class MemberCovered {
        @ParameterizedTest(name = "member {0} → {1}")
        @CsvSource(delimiter = '|', textBlock = """
                567dacf4-a6e5-461f-b094-8ded155a5a7e | true  | Asha Verma is insured
                7971f862-38fe-4a72-9193-f8d5a7bc591b | true  | Vikram Verma is insured
                6ee0f2b2-1c47-43a7-b6f6-3af17479a96f | false | not insured under POL-2026-00017
                ''                                   | false | names no patient
                """)
        void table(String member, boolean passed, String message) {
            UUID id = member == null || member.isBlank() ? null : UUID.fromString(member);
            var ctx = withClaim(eligible(), claim(id, "2026-03-10", "Hospitalisation", "POL-2026-00017"));
            check(new MemberCoveredRule(), ctx, passed, message);
        }
    }

    @Nested
    class TreatmentCovered {
        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', textBlock = """
                Hospitalisation    | true  | Hospitalisation is covered
                DAYCARE            | true  | Day care procedure is covered
                day care procedure | true  | Day care procedure is covered
                Cosmetic treatment | false | Cosmetic treatment is not covered
                Acupuncture        | false | no rule for 'Acupuncture'
                """)
        void table(String category, boolean passed, String message) {
            var ctx = withClaim(eligible(), claim(ASHA_00017, "2026-03-10", category, "POL-2026-00017"));
            check(new TreatmentCoveredRule(), ctx, passed, message);
        }
    }

    @Nested
    class WaitingPeriod {
        /** POL-2026-00017 starts 2026-01-01; 2026-09-28 is exactly 270 days later. */
        @ParameterizedTest(name = "{0} admitted {1} → {2}")
        @CsvSource(delimiter = '|', textBlock = """
                Maternity       | 2026-09-28 | true  | admitted after 270 days
                Maternity       | 2026-09-27 | false | 270-day waiting period
                Hospitalisation | 2026-01-01 | true  | 0-day waiting period
                Hospitalisation | 2025-12-31 | false | admitted after -1 days
                Acupuncture     | 2026-03-10 | false | Unknown treatment
                Maternity       | ''         | false | no admission date
                """)
        void table(String category, String admitted, boolean passed, String message) {
            var ctx = withClaim(eligible(), claim(ASHA_00017, admitted, category, "POL-2026-00017"));
            check(new WaitingPeriodRule(), ctx, passed, message);
        }
    }

    @Nested
    class BalanceAvailable {
        @ParameterizedTest(name = "remaining {0} → {1}")
        @CsvSource(delimiter = '|', textBlock = """
                500000.00 | true  | Remaining balance 500000.00
                0.01      | true  | Remaining balance 0.01
                0.00      | false | Remaining balance 0.00
                -5.00     | false | Remaining balance -5.00
                ''        | false | unknown
                """)
        void table(String remaining, boolean passed, String message) {
            check(new BalanceAvailableRule(), withBalance(eligible(), remaining), passed, message);
        }
    }

    @Nested
    class Fixture {
        @ParameterizedTest(name = "{0} passes on the eligible fixture")
        @CsvSource({"POLICY_ACTIVE", "WITHIN_POLICY_PERIOD", "MEMBER_COVERED", "TREATMENT_COVERED", "WAITING_PERIOD", "BALANCE_AVAILABLE"})
        void everyRulePassesTheEligibleFixture(String name) {
            EligibilityRule rule = java.util.stream.Stream.of(new PolicyActiveRule(), new WithinPolicyPeriodRule(),
                            new MemberCoveredRule(), new TreatmentCoveredRule(), new WaitingPeriodRule(), new BalanceAvailableRule())
                    .filter(r -> r.name().equals(name)).findFirst().orElseThrow();
            assertThat(rule.evaluate(eligible()).passed()).isTrue();
            assertThat(LocalDate.parse("2026-03-10")).isBetween(POLICY_00017.startDate(), POLICY_00017.endDate());
        }
    }
}
