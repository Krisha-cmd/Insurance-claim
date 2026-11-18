package com.claims.approval.repository;

import com.claims.approval.domain.SettlementTxParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementTxParticipantRepository
        extends JpaRepository<SettlementTxParticipant, SettlementTxParticipant.Key> {

    List<SettlementTxParticipant> findByIdTxId(UUID txId);
}
