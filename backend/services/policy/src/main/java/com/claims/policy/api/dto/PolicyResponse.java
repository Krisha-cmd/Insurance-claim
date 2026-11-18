package com.claims.policy.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A policy as the frontend's policy object (mock policyView): number, holder, plan name, validity, sum
 * insured and what is left of it, deductible and co-pay, members.
 *
 * @param holderId         the policyholder id (the JWT's policyholderId claim)
 * @param remainingBalance sumInsured − utilised − reserved
 */
public record PolicyResponse(UUID policyId, String policyNumber, UUID holderId, String holderName, String plan,
                             String status, LocalDate validFrom, LocalDate validTo, BigDecimal sumInsured,
                             BigDecimal utilised, BigDecimal reserved, BigDecimal remainingBalance,
                             BigDecimal deductible, BigDecimal coPayPercent, BigDecimal roomRentLimit,
                             String currency, List<MemberDto> members) {
}
