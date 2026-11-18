package com.claims.assessment.api.dto;

import java.math.BigDecimal;

/**
 * One step of the calculation. Running totals ("Claimed amount", "After deductible", "Proposed amount") are
 * positive; deductions ("Deductible", "Co-pay") are negative, as in the gRPC {@code AssessmentLine}.
 */
public record BreakdownLine(String label, BigDecimal amount) {
}
