package com.claims.approval.api.dto;

import com.claims.approval.domain.Decision;

import java.util.UUID;

/**
 * Response of a decision or a retry.
 *
 * @param status  the claim status Approval set (APPROVED / REJECTED)
 * @param txId    the settlement started, or null for a rejection
 * @param attempt the settlement attempt number, or null for a rejection
 */
public record DecisionResponse(UUID claimId, Decision decision, String status, UUID txId, Integer attempt) {
}
