-- Claims DB (see "Claim Database" in the root README). Columns beyond the ER diagram are marked (UI):
-- the frontend shows them and they would otherwise need a call to Policy on every read.

CREATE SEQUENCE claim_no_seq START 1;

CREATE TABLE claim (
    id                 uuid          PRIMARY KEY,
    claim_no           varchar(20)   NOT NULL,            -- CLM-<yyyy>-<6 digits>
    policy_no          varchar(30)   NOT NULL,            -- ref: Policy DB
    member_id          uuid          NOT NULL,            -- ref: Policy DB
    member_name        varchar(200)  NOT NULL,            -- (UI) from Policy at filing time
    member_relation    varchar(20),                       -- (UI)
    policyholder_id    uuid          NOT NULL,            -- (UI) ref: Policy DB
    policyholder_name  varchar(200),                      -- (UI)
    submitted_by       uuid          NOT NULL,            -- ref: Login DB (JWT sub)
    hospital_name      varchar(200)  NOT NULL,
    admission_date     date          NOT NULL,
    discharge_date     date          NOT NULL,
    diagnosis          varchar(500)  NOT NULL,
    treatment_category varchar(60)   NOT NULL,
    claimed_amount     numeric(14,2) NOT NULL,
    proposed_amount    numeric(14,2),                     -- (UI) from Assessment
    approved_amount    numeric(14,2),                     -- set when settled
    eligible           boolean,                           -- (UI) latest eligibility result
    rejection_reason   varchar(2000),                     -- (UI) the adjuster's comment
    status             varchar(30)   NOT NULL,
    version            bigint        NOT NULL DEFAULT 0,  -- optimistic lock
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL,            -- (UI) last status change

    CONSTRAINT uq_claim_no UNIQUE (claim_no),
    CONSTRAINT ck_claim_amount CHECK (claimed_amount > 0),
    CONSTRAINT ck_claim_dates CHECK (discharge_date >= admission_date),
    CONSTRAINT ck_claim_status CHECK (status IN ('SUBMITTED', 'DOCS_PENDING', 'DOCS_VERIFIED', 'ELIGIBILITY_CHECKED',
        'ASSESSED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'SETTLING', 'SETTLED', 'SETTLEMENT_FAILED'))
);

CREATE INDEX idx_claim_submitted_by ON claim (submitted_by, created_at DESC);
CREATE INDEX idx_claim_status       ON claim (status, created_at);
CREATE INDEX idx_claim_policy_no    ON claim (policy_no);

CREATE TABLE claim_document (
    id                  uuid          PRIMARY KEY,
    claim_id            uuid          NOT NULL REFERENCES claim (id),
    doc_type            varchar(30)   NOT NULL,
    file_name           varchar(255)  NOT NULL,           -- (UI) the uploader's file name
    content_type        varchar(100)  NOT NULL,           -- (UI)
    size_bytes          bigint,                           -- (UI) as declared by the browser
    s3_key              varchar(500)  NOT NULL,
    checksum            varchar(128),                     -- from the verifier
    verification_status varchar(20)   NOT NULL,
    rejection_reason    varchar(500),
    uploaded_at         timestamptz   NOT NULL,           -- when the upload URL was issued
    verified_at         timestamptz,                      -- (UI)
    superseded_by       uuid,                             -- set when a newer upload of the same type replaces it

    CONSTRAINT uq_claim_document_key UNIQUE (s3_key),
    CONSTRAINT ck_document_type CHECK (doc_type IN ('DISCHARGE_SUMMARY', 'FINAL_BILL', 'PRESCRIPTION', 'LAB_REPORT', 'ID_PROOF')),
    CONSTRAINT ck_document_status CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED'))
);

CREATE INDEX idx_claim_document_claim ON claim_document (claim_id);

CREATE TABLE claim_status_history (
    id          bigserial    PRIMARY KEY,
    claim_id    uuid         NOT NULL REFERENCES claim (id),
    from_status varchar(30),                              -- NULL for the first entry
    to_status   varchar(30)  NOT NULL,
    changed_by  uuid,                                     -- ref: Login DB; NULL when a service made the change
    actor       varchar(250) NOT NULL,                    -- "ADJUSTER:Rohan Mehta", "SYSTEM:approval" …
    comment     varchar(2000),                            -- required for adjuster changes
    changed_at  timestamptz  NOT NULL
);

CREATE INDEX idx_claim_status_history_claim ON claim_status_history (claim_id, id);

CREATE TABLE eligibility_check (
    id               uuid         PRIMARY KEY,
    claim_id         uuid         NOT NULL REFERENCES claim (id),
    eligible         boolean      NOT NULL,
    rule_results     jsonb        NOT NULL,               -- [{rule, label, passed, detail}]
    overridden       boolean      NOT NULL DEFAULT false,
    override_comment varchar(2000),
    checked_by       varchar(250) NOT NULL,
    checked_at       timestamptz  NOT NULL
);

CREATE INDEX idx_eligibility_check_claim ON eligibility_check (claim_id, checked_at DESC);

-- This service's 3PC log (ParticipantTx). claim_id is NULL only for a tombstone (Abort before CanCommit).
CREATE TABLE settlement_participation (
    tx_id      uuid          PRIMARY KEY,
    claim_id   uuid          REFERENCES claim (id),
    amount     numeric(14,2),
    attempt    integer       NOT NULL,
    state      varchar(20)   NOT NULL,
    vote       varchar(10),
    reason     varchar(500),
    created_at timestamptz   NOT NULL,
    updated_at timestamptz   NOT NULL,
    version    bigint        NOT NULL DEFAULT 0,

    CONSTRAINT ck_participation_state CHECK (state IN ('PREPARED', 'PRECOMMITTED', 'COMMITTED', 'ABORTED')),
    CONSTRAINT ck_participation_vote  CHECK (vote IN ('YES', 'NO'))
);

CREATE INDEX idx_participation_open ON settlement_participation (state, updated_at)
    WHERE state IN ('PREPARED', 'PRECOMMITTED');
