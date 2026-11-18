package com.claims.participant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for a participant's tx log. A Spring Data repository implements it with default methods:
 * <pre>
 * interface SettlementParticipationRepository extends JpaRepository&lt;SettlementParticipation, UUID&gt;,
 *         ParticipantTxStore&lt;SettlementParticipation&gt; {
 *     List&lt;SettlementParticipation&gt; findByStateAndUpdatedAtLessThanEqual(ParticipantTxState s, Instant t);
 *     default Optional&lt;SettlementParticipation&gt; findTx(UUID id) { return findById(id); }
 *     default SettlementParticipation saveTx(SettlementParticipation t) { return saveAndFlush(t); }
 *     default List&lt;SettlementParticipation&gt; findStale(ParticipantTxState s, Instant t) { return findByStateAndUpdatedAtLessThanEqual(s, t); }
 * }
 * </pre>
 * {@code saveTx} must flush, so a duplicate tx id fails inside the transaction.
 */
public interface ParticipantTxStore<T extends ParticipantTx> {

    Optional<T> findTx(UUID txId);

    T saveTx(T tx);

    /** Rows in {@code state} last changed at or before {@code before}. */
    List<T> findStale(ParticipantTxState state, Instant before);
}
