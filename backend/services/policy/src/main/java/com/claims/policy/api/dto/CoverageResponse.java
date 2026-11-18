package com.claims.policy.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/policies/{policyNo}/coverage, in the frontend's shape: validity, sum insured, deductible,
 * co-pay, the treatments table and the exclusions list (the plan's not-covered categories).
 */
public record CoverageResponse(String policyNumber, String plan, LocalDate validFrom, LocalDate validTo,
                               BigDecimal sumInsured, BigDecimal deductible, BigDecimal coPayPercent,
                               BigDecimal roomRentLimit, List<CoverageRuleDto> treatments,
                               List<Exclusion> exclusions) {

    public record Exclusion(String keyword, String label) {
    }
}
