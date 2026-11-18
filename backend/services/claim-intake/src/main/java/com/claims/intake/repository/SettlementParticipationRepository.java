package com.claims.intake.repository;

import com.claims.intake.domain.SettlementParticipation;
import com.claims.participant.ParticipantTxState;
import com.claims.participant.ParticipantTxStore;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementParticipationRepository
        extends JpaRepository<SettlementParticipation, UUID>, ParticipantTxStore<SettlementParticipation> {

    List<SettlementParticipation> findByStateAndUpdatedAtLessThanEqual(ParticipantTxState state, Instant before);

    List<SettlementParticipation> findByClaimId(UUID claimId);

    @Override
    default Optional<SettlementParticipation> findTx(UUID txId) {
        return findById(txId);
    }

    @Override
    default SettlementParticipation saveTx(SettlementParticipation tx) {
        return saveAndFlush(tx);
    }

    @Override
    default List<SettlementParticipation> findStale(ParticipantTxState state, Instant before) {
        return findByStateAndUpdatedAtLessThanEqual(state, before);
    }
}
