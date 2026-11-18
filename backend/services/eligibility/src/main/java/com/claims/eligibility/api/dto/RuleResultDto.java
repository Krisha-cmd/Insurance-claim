package com.claims.eligibility.api.dto;

/** One check, in the frontend's shape ({@code checks[]} in the mock): {rule, label, passed, detail}. */
public record RuleResultDto(String rule, String label, boolean passed, String detail) {
}
