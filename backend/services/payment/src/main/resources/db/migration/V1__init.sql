-- Payments DB (see "Payments Database" in the root README).

CREATE TABLE payee_account (
    id                uuid         PRIMARY KEY,
    user_id           uuid         NOT NULL,             -- ref: Login DB (the policyholder's user id)
    account_holder    varchar(200) NOT NULL,
    account_no_masked varchar(20)  NOT NULL,             -- e.g. XXXXXX4321; the full number is never stored here
    ifsc              varchar(11)  NOT NULL,
    verified          boolean      NOT NULL DEFAULT false,

    CONSTRAINT ck_payee_ifsc CHECK (ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$')
);

CREATE INDEX idx_payee_user ON payee_account (user_id);

CREATE TABLE payment (
    id               uuid          PRIMARY KEY,
    claim_id         uuid          NOT NULL,              -- ref: Claims DB
    payee_account_id uuid          NOT NULL REFERENCES payee_account (id),
    amount           numeric(14,2) NOT NULL,
    currency         varchar(3)    NOT NULL DEFAULT 'INR',
    status           varchar(12)   NOT NULL,
    auth_id          varchar(64),                         -- the gateway's authorization id
    gateway_ref      varchar(64),                         -- the gateway's capture reference (UTR)
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL DEFAULT 0,

    CONSTRAINT ck_payment_status CHECK (status IN ('AUTHORIZED', 'CAPTURED', 'VOIDED', 'FAILED')),
    CONSTRAINT ck_payment_amount CHECK (amount > 0)
);

-- A claim is paid at most once: at most one AUTHORIZED or CAPTURED payment per claim.
-- (A retried settlement after an abort gets a new payment; the voided/failed ones stay as history.)
CREATE UNIQUE INDEX uq_payment_live_per_claim ON payment (claim_id) WHERE status IN ('AUTHORIZED', 'CAPTURED');
CREATE INDEX idx_payment_claim ON payment (claim_id);

CREATE TABLE payment_attempt (
    id            bigserial    PRIMARY KEY,
    payment_id    uuid         NOT NULL REFERENCES payment (id),
    action        varchar(10)  NOT NULL,
    result_code   varchar(30)  NOT NULL,                  -- OK, HTTP_503, TIMEOUT, NETWORK …
    error_message varchar(500),
    duration_ms   integer,
    attempted_at  timestamptz  NOT NULL,

    CONSTRAINT ck_attempt_action CHECK (action IN ('AUTHORIZE', 'CAPTURE', 'VOID'))
);

CREATE INDEX idx_attempt_payment ON payment_attempt (payment_id, id);

-- This service's 3PC log (ParticipantTx). payment_id is NULL for a NO vote taken before a payment existed,
-- and for a tombstone.
CREATE TABLE payment_tx_log (
    tx_id      uuid          PRIMARY KEY,
    payment_id uuid          REFERENCES payment (id),
    claim_id   uuid,
    amount     numeric(14,2),
    attempt    integer       NOT NULL,
    state      varchar(20)   NOT NULL,
    vote       varchar(10),
    reason     varchar(500),
    created_at timestamptz   NOT NULL,
    updated_at timestamptz   NOT NULL,
    version    bigint        NOT NULL DEFAULT 0,

    CONSTRAINT ck_txlog_state CHECK (state IN ('PREPARED', 'PRECOMMITTED', 'COMMITTED', 'ABORTED')),
    CONSTRAINT ck_txlog_vote  CHECK (vote IN ('YES', 'NO'))
);

CREATE INDEX idx_txlog_open ON payment_tx_log (state, updated_at) WHERE state IN ('PREPARED', 'PRECOMMITTED');
CREATE INDEX idx_txlog_payment ON payment_tx_log (payment_id);
