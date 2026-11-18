package com.claims.policy.repository;

import com.claims.participant.ParticipantTxState;
import com.claims.participant.ParticipantTxStore;
import com.claims.policy.domain.LimitReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LimitReservationRepository extends JpaRepository<LimitReservation, UUID>, ParticipantTxStore<LimitReservation> {

    List<LimitReservation> findByStateAndUpdatedAtLessThanEqual(ParticipantTxState state, Instant before);

    List<LimitReservation> findByPolicyId(UUID policyId);

    @Override
    default Optional<LimitReservation> findTx(UUID txId) {
        return findById(txId);
    }

    @Override
    default LimitReservation saveTx(LimitReservation tx) {
        return saveAndFlush(tx);
    }

    @Override
    default List<LimitReservation> findStale(ParticipantTxState state, Instant before) {
        return findByStateAndUpdatedAtLessThanEqual(state, before);
    }
}
