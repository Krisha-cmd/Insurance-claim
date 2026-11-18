-- Approval DB. settlement_tx + settlement_tx_participant are the 3PC coordinator's durable log:
-- the coordinator writes the next state here BEFORE every network phase, so after a crash
-- CoordinatorRecovery can finish every transaction from this log alone.

CREATE TABLE approval_decision (
    id          uuid          PRIMARY KEY,
    claim_id    uuid          NOT NULL,
    decision    varchar(10)   NOT NULL,
    amount      numeric(14,2),                      -- NULL for REJECTED
    comment     varchar(2000) NOT NULL,
    adjuster_id uuid          NOT NULL,             -- login user id (JWT sub)
    decided_at  timestamptz   NOT NULL,

    CONSTRAINT uq_approval_decision_claim UNIQUE (claim_id),   -- one decision per claim
    CONSTRAINT ck_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_approval_amount   CHECK (decision = 'REJECTED' OR amount IS NOT NULL)
);

CREATE TABLE settlement_tx (
    tx_id      uuid          PRIMARY KEY,
    claim_id   uuid          NOT NULL REFERENCES approval_decision (claim_id),
    amount     numeric(14,2) NOT NULL,
    state      varchar(20)   NOT NULL,
    attempt    integer       NOT NULL,
    created_at timestamptz   NOT NULL,
    updated_at timestamptz   NOT NULL,
    version    bigint        NOT NULL DEFAULT 0,    -- optimistic locking

    CONSTRAINT ck_settlement_tx_state CHECK (state IN
        ('WAITING_VOTES', 'PRECOMMITTING', 'COMMITTING', 'COMMITTED', 'ABORTING', 'ABORTED')),
    CONSTRAINT ck_settlement_tx_attempt CHECK (attempt > 0),
    CONSTRAINT uq_settlement_tx_attempt UNIQUE (claim_id, attempt)
);

-- At most one unfinished settlement per claim (guards concurrent retries).
CREATE UNIQUE INDEX uq_settlement_tx_one_active ON settlement_tx (claim_id)
    WHERE state IN ('WAITING_VOTES', 'PRECOMMITTING', 'COMMITTING', 'ABORTING');

-- What the recovery sweep scans.
CREATE INDEX idx_settlement_tx_unfinished ON settlement_tx (updated_at)
    WHERE state IN ('WAITING_VOTES', 'PRECOMMITTING', 'COMMITTING', 'ABORTING');

CREATE TABLE settlement_tx_participant (
    tx_id           uuid         NOT NULL REFERENCES settlement_tx (tx_id) ON DELETE CASCADE,
    participant     varchar(50)  NOT NULL,          -- name from claims.coordinator.participants
    vote            varchar(10),                    -- NULL until it votes (or if it never does)
    state           varchar(20)  NOT NULL DEFAULT 'UNKNOWN',
    last_contact_at timestamptz,
    error           varchar(500),                   -- last failure or NO reason

    PRIMARY KEY (tx_id, participant),
    CONSTRAINT ck_participant_vote  CHECK (vote IN ('YES', 'NO')),
    CONSTRAINT ck_participant_state CHECK (state IN
        ('UNKNOWN', 'PREPARED', 'PRECOMMITTED', 'COMMITTED', 'ABORTED'))
);
