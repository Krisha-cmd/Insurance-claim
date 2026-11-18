package com.claims.intake.domain;

import com.claims.participant.ParticipantTx;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Claim Intake's 3PC log (one row per settlement tx). All columns come from {@link ParticipantTx}. */
@Entity
@Table(name = "settlement_participation")
public class SettlementParticipation extends ParticipantTx {
}
