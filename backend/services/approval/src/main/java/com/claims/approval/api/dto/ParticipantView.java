package com.claims.approval.api.dto;

import java.time.Instant;

/**
 * One participant in the settlement stepper.
 *
 * @param name          display key the frontend maps to a label (CLAIM_INTAKE, POLICY, PAYMENT)
 * @param participant   configured name (claim-intake, policy, payment)
 * @param vote          PENDING, YES or NO (no vote within the deadline counts as NO)
 * @param state         last acknowledged state: UNKNOWN, PREPARED, PRECOMMITTED, COMMITTED, ABORTED
 * @param preCommitAck  acknowledged PreCommit
 * @param doCommitAck   acknowledged DoCommit
 * @param aborted       acknowledged Abort
 * @param error         last failure or NO reason
 */
public record ParticipantView(String name, String participant, String vote, String state,
                              boolean preCommitAck, boolean doCommitAck, boolean aborted,
                              Instant lastContactAt, String error) {
}
