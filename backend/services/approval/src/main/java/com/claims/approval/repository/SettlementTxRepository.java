package com.claims.approval.repository;

import com.claims.approval.domain.CoordinatorState;
import com.claims.approval.domain.SettlementTx;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementTxRepository extends JpaRepository<SettlementTx, UUID> {

    /** The latest attempt for a claim. */
    Optional<SettlementTx> findFirstByClaimIdOrderByAttemptDesc(UUID claimId);

    List<SettlementTx> findByStateInAndUpdatedAtLessThanEqualOrderByUpdatedAt(Collection<CoordinatorState> states,
                                                                             Instant cutoff);
}
