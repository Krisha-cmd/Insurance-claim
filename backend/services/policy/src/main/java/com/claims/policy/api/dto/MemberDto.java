package com.claims.policy.api.dto;

import java.time.LocalDate;
import java.util.UUID;

/** @param relation SELF | SPOUSE | CHILD | PARENT */
public record MemberDto(UUID id, String name, String relation, LocalDate dob) {
}
