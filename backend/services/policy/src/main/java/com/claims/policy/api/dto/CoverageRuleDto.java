package com.claims.policy.api.dto;

import java.math.BigDecimal;

/**
 * One row of the coverage table.
 *
 * @param category  HOSPITALISATION, SURGERY, DAYCARE, MATERNITY, … (what Eligibility matches on)
 * @param treatment the label the frontend shows and a claim's treatmentType carries, e.g. "Day care procedure"
 * @param subLimit  null when not covered
 */
public record CoverageRuleDto(String category, String treatment, boolean covered, BigDecimal subLimit,
                              int waitingPeriodDays, String notes) {
}
