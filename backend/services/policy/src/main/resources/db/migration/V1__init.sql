-- Policy DB (see "Policy Database" in the root README).

CREATE TABLE policyholder (
    id            uuid         PRIMARY KEY,
    user_id       uuid         NOT NULL,            -- ref: Login DB (users.id)
    full_name     varchar(200) NOT NULL,
    date_of_birth date,
    phone         varchar(30),
    email         varchar(255),

    CONSTRAINT uq_policyholder_user UNIQUE (user_id)
);

CREATE TABLE plan (
    id              uuid          PRIMARY KEY,
    name            varchar(100)  NOT NULL,
    deductible      numeric(14,2) NOT NULL,
    copay_pct       numeric(5,2)  NOT NULL,          -- e.g. 10.00 = 10 %
    room_rent_limit numeric(14,2),                   -- per day

    CONSTRAINT ck_plan_copay CHECK (copay_pct BETWEEN 0 AND 100)
);

CREATE TABLE coverage_rule (
    id                  uuid          PRIMARY KEY,
    plan_id             uuid          NOT NULL REFERENCES plan (id),
    treatment_category  varchar(30)   NOT NULL,      -- HOSPITALISATION, SURGERY, MATERNITY, DAYCARE, ...
    covered             boolean       NOT NULL,
    sub_limit           numeric(14,2),               -- NULL when not covered
    waiting_period_days integer       NOT NULL DEFAULT 0,

    CONSTRAINT uq_coverage_rule UNIQUE (plan_id, treatment_category),
    CONSTRAINT ck_coverage_category CHECK (treatment_category IN
        ('HOSPITALISATION', 'SURGERY', 'MATERNITY', 'DAYCARE', 'DIAGNOSTICS', 'EMERGENCY', 'COSMETIC')),
    CONSTRAINT ck_coverage_limit CHECK (NOT covered OR sub_limit IS NOT NULL)
);

CREATE TABLE policy (
    id              uuid          PRIMARY KEY,
    policy_no       varchar(30)   NOT NULL,
    policyholder_id uuid          NOT NULL REFERENCES policyholder (id),
    plan_id         uuid          NOT NULL REFERENCES plan (id),
    start_date      date          NOT NULL,
    end_date        date          NOT NULL,
    sum_insured     numeric(14,2) NOT NULL,
    status          varchar(10)   NOT NULL,

    CONSTRAINT uq_policy_no UNIQUE (policy_no),
    CONSTRAINT ck_policy_status CHECK (status IN ('ACTIVE', 'LAPSED')),
    CONSTRAINT ck_policy_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_policy_sum CHECK (sum_insured > 0)
);

CREATE INDEX idx_policy_holder ON policy (policyholder_id);

CREATE TABLE insured_member (
    id            uuid         PRIMARY KEY,
    policy_id     uuid         NOT NULL REFERENCES policy (id),
    full_name     varchar(200) NOT NULL,
    relation      varchar(10)  NOT NULL,
    date_of_birth date,

    CONSTRAINT ck_member_relation CHECK (relation IN ('SELF', 'SPOUSE', 'CHILD', 'PARENT'))
);

CREATE INDEX idx_member_policy ON insured_member (policy_id);

-- One row per policy. Settlements reserve against it with optimistic locking (version).
CREATE TABLE policy_balance (
    policy_id uuid          PRIMARY KEY REFERENCES policy (id),
    utilised  numeric(14,2) NOT NULL DEFAULT 0,
    reserved  numeric(14,2) NOT NULL DEFAULT 0,
    version   bigint        NOT NULL DEFAULT 0,

    CONSTRAINT ck_balance_nonnegative CHECK (utilised >= 0 AND reserved >= 0)
);

-- This service's 3PC log (ParticipantTx; tx_id is the key). policy_id is NULL for a tombstone or a NO vote
-- whose claim could not be matched to a policy.
CREATE TABLE limit_reservation (
    tx_id      uuid          PRIMARY KEY,
    policy_id  uuid          REFERENCES policy (id),
    claim_id   uuid,                                -- ref: Claims DB
    amount     numeric(14,2),
    attempt    integer       NOT NULL,
    state      varchar(20)   NOT NULL,
    vote       varchar(10),
    reason     varchar(500),
    created_at timestamptz   NOT NULL,
    updated_at timestamptz   NOT NULL,
    version    bigint        NOT NULL DEFAULT 0,

    CONSTRAINT ck_reservation_state CHECK (state IN ('PREPARED', 'PRECOMMITTED', 'COMMITTED', 'ABORTED')),
    CONSTRAINT ck_reservation_vote  CHECK (vote IN ('YES', 'NO'))
);

CREATE INDEX idx_reservation_open ON limit_reservation (state, updated_at) WHERE state IN ('PREPARED', 'PRECOMMITTED');
CREATE INDEX idx_reservation_policy ON limit_reservation (policy_id);
