package com.claims.assessment.service;

import com.claims.assessment.api.dto.BreakdownLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The calculation as a pure function: no Spring context, no database. */
class AssessmentCalculatorTest {

    private final AssessmentCalculator calculator = new AssessmentCalculator();

    /** A plan with one rule for the category under test. {@code subLimit}: blank = no sub-limit, NC = not covered. */
    private static Coverage plan(String subLimit, String deductible, String copayPct) {
        Coverage.Rule rule = switch (subLimit == null ? "" : subLimit) {
            case "" -> new Coverage.Rule("HOSPITALISATION", "Hospitalisation", true, null);
            case "NC" -> new Coverage.Rule("HOSPITALISATION", "Hospitalisation", false, null);
            default -> new Coverage.Rule("HOSPITALISATION", "Hospitalisation", true, new BigDecimal(subLimit));
        };
        return new Coverage(new BigDecimal(deductible), new BigDecimal(copayPct), List.of(rule));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # case                              | claimed   | subLimit | deductible | copay% | remaining | afterCap  | deducted | afterDed | copay   | proposed
            claimed below the deductible → 0    | 3000      | 50000    | 5000       | 10     | 500000    | 3000.00   | 3000.00  | 0.00     | 0.00    | 0.00
            claimed equal to the deductible     | 5000      | 50000    | 5000       | 10     | 500000    | 5000.00   | 5000.00  | 0.00     | 0.00    | 0.00
            sub-limit cap                       | 60000     | 50000    | 5000       | 10     | 500000    | 50000.00  | 5000.00  | 45000.00 | 4500.00 | 40500.00
            under the sub-limit                 | 67500     | 300000   | 5000       | 10     | 500000    | 67500.00  | 5000.00  | 62500.00 | 6250.00 | 56250.00
            no sub-limit for the category       | 60000     |          | 5000       | 10     | 500000    | 60000.00  | 5000.00  | 55000.00 | 5500.00 | 49500.00
            category not covered → 0            | 60000     | NC       | 5000       | 10     | 500000    | 0.00      | 0.00     | 0.00     | 0.00    | 0.00
            remaining-balance cap               | 100000    | 300000   | 5000       | 10     | 40000     | 100000.00 | 5000.00  | 95000.00 | 9500.00 | 40000.00
            nothing left on the policy → 0      | 100000    | 300000   | 5000       | 10     | 0         | 100000.00 | 5000.00  | 95000.00 | 9500.00 | 0.00
            no co-pay                           | 67500     | 300000   | 5000       | 0      | 500000    | 67500.00  | 5000.00  | 62500.00 | 0.00    | 62500.00
            rounding: co-pay 12.505 → 12.51     | 100.04    |          | 0          | 12.5   | 500000    | 100.04    | 0.00     | 100.04   | 12.51   | 87.53
            rounding: co-pay 1543.20875         | 12345.67  |          | 0          | 12.5   | 500000    | 12345.67  | 0.00     | 12345.67 | 1543.21 | 10802.46
            rounding: claimed 1000.005          | 1000.005  |          | 0          | 0      | 500000    | 1000.01   | 0.00     | 1000.01  | 0.00    | 1000.01
            """)
    void calculatesEachStep(String name, String claimed, String subLimit, String deductible, String copayPct,
                            String remaining, String afterCap, String deducted, String afterDed, String copay,
                            String proposed) {
        Calculation c = calculator.calculate(new BigDecimal(claimed), plan(subLimit, deductible, copayPct),
                new Balance(new BigDecimal(remaining)), "Hospitalisation");

        // Exact BigDecimal equality, so the scale (2) is checked too.
        assertThat(c.afterCap()).isEqualTo(new BigDecimal(afterCap));
        assertThat(c.deductible()).isEqualTo(new BigDecimal(deducted));
        assertThat(c.afterDeductible()).isEqualTo(new BigDecimal(afterDed));
        assertThat(c.copayAmount()).isEqualTo(new BigDecimal(copay));
        assertThat(c.proposed()).isEqualTo(new BigDecimal(proposed));
        assertThat(c.lines().get(c.lines().size() - 1).amount()).isEqualTo(new BigDecimal(proposed));
    }

    @Test
    void breakdownListsEveryStepInOrder() {
        Calculation c = calculator.calculate(new BigDecimal("60000"), plan("50000", "5000", "10"),
                new Balance(new BigDecimal("500000")), "Hospitalisation");

        assertThat(c.lines()).containsExactly(
                new BreakdownLine("Claimed amount", new BigDecimal("60000.00")),
                new BreakdownLine("Capped at the Hospitalisation sub-limit of 50000.00", new BigDecimal("50000.00")),
                new BreakdownLine("Deductible", new BigDecimal("-5000.00")),
                new BreakdownLine("After deductible", new BigDecimal("45000.00")),
                new BreakdownLine("Co-pay (10%)", new BigDecimal("-4500.00")),
                new BreakdownLine("Proposed amount", new BigDecimal("40500.00")));
    }

    @Test
    void theBalanceCapIsAStepOfItsOwn() {
        Calculation c = calculator.calculate(new BigDecimal("100000"), plan("300000", "5000", "10"),
                new Balance(new BigDecimal("40000")), "Hospitalisation");

        assertThat(c.balanceCut()).isEqualTo(new BigDecimal("45500.00"));
        assertThat(c.lines()).contains(
                new BreakdownLine("Capped at the remaining sum insured of 40000.00", new BigDecimal("-45500.00")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hospitalisation", "HOSPITALISATION", "hospitalisation", "  Hospitalisation "})
    void matchesTheRuleByLabelOrCodeIgnoringCase(String category) {
        Calculation c = calculator.calculate(new BigDecimal("60000"), plan("50000", "0", "0"),
                new Balance(new BigDecimal("500000")), category);

        assertThat(c.subLimit()).isEqualTo(new BigDecimal("50000.00"));
        assertThat(c.proposed()).isEqualTo(new BigDecimal("50000.00"));
    }

    @Test
    void anUnknownCategoryHasNoSubLimit() {
        Calculation c = calculator.calculate(new BigDecimal("60000"), plan("50000", "0", "0"),
                new Balance(new BigDecimal("500000")), "Acupuncture");

        assertThat(c.subLimit()).isNull();
        assertThat(c.proposed()).isEqualTo(new BigDecimal("60000.00"));
        assertThat(c.lines().get(1).label()).isEqualTo("No sub-limit for Acupuncture");
    }

    @Test
    void negativeInputsAreRejectedOrIgnored() {
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("-1"), plan("", "0", "0"),
                new Balance(BigDecimal.TEN), "Hospitalisation")).isInstanceOf(IllegalArgumentException.class);

        // A negative remaining balance (over-utilised policy) caps at 0, never below.
        Calculation c = calculator.calculate(new BigDecimal("1000"), plan("", "0", "0"),
                new Balance(new BigDecimal("-50")), "Hospitalisation");
        assertThat(c.proposed()).isEqualTo(new BigDecimal("0.00"));
    }
}
