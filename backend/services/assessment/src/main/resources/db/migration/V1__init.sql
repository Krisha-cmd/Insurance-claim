-- Assessment DB. One assessment per claim, with every step of the calculation stored so the breakdown
-- (claimed → capped → minus deductible → minus co-pay → proposed) can be shown exactly as it was computed.

CREATE TABLE assessment (
    id                 uuid          PRIMARY KEY,
    claim_id           uuid          NOT NULL,                 -- ref: Claims DB
    -- Claim context, copied from ClaimDetails when assessed (no cross-service lookup on reads).
    policy_no          varchar(40)   NOT NULL,                 -- ref: Policy DB
    treatment_category varchar(60)   NOT NULL,
    policyholder_id    uuid,                                   -- owner check: JWT policyholderId
    submitted_by       uuid,                                   -- owner check: JWT sub
    -- The calculation, one column per step.
    claimed_amount     numeric(14,2) NOT NULL,
    sub_limit_applied  numeric(14,2),                          -- NULL: no sub-limit for the category
    after_cap          numeric(14,2) NOT NULL,                 -- min(claimed, sub-limit)
    deductible         numeric(14,2) NOT NULL,                 -- the part of the plan deductible actually deducted
    after_deductible   numeric(14,2) NOT NULL,                 -- max(after_cap - plan deductible, 0)
    copay_pct          numeric(5,2)  NOT NULL,                 -- e.g. 10.00 (percent)
    copay_amount       numeric(14,2) NOT NULL,
    remaining_balance  numeric(14,2) NOT NULL,                 -- the policy's remaining sum insured at the time
    proposed_amount    numeric(14,2) NOT NULL,                 -- min(after_deductible - copay, remaining_balance)
    final_amount       numeric(14,2) NOT NULL,                 -- the adjusted amount, else proposed_amount
    adjusted           boolean       NOT NULL DEFAULT false,
    currency           varchar(3)    NOT NULL,
    version            bigint        NOT NULL DEFAULT 0,       -- optimistic locking
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL,

    CONSTRAINT uq_assessment_claim UNIQUE (claim_id),
    CONSTRAINT ck_assessment_amounts CHECK (claimed_amount >= 0 AND proposed_amount >= 0 AND final_amount >= 0)
);

CREATE TABLE assessment_adjustment (
    id            uuid          PRIMARY KEY,
    assessment_id uuid          NOT NULL REFERENCES assessment (id) ON DELETE CASCADE,
    before_amount numeric(14,2) NOT NULL,
    after_amount  numeric(14,2) NOT NULL,
    comment       varchar(2000) NOT NULL,
    adjuster_id   uuid          NOT NULL,                      -- ref: Login DB (JWT sub)
    adjuster_name varchar(200),
    adjusted_at   timestamptz   NOT NULL,

    CONSTRAINT ck_adjustment_amount CHECK (after_amount > 0)
);

CREATE INDEX idx_adjustment_assessment ON assessment_adjustment (assessment_id, adjusted_at);
