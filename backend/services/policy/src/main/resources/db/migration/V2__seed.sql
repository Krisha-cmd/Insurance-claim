-- Demo data. Fixed ids so other services and tests can refer to them:
--
--   policyholder patient1     a2b56321-c8b8-4159-93d6-3cf613b3c556  (= users.policyholder_id in login's V2 seed)
--   login user patient1       4e33233c-a255-4c75-963a-6809cd3b0cdd
--   plan Family Health Plus   787465cc-3880-4abe-8d7b-463662e0ff9b
--   POL-2026-00017 (active)   f87ebb00-4f1a-4110-a200-653ccd12b9c7
--     member SELF   Asha Verma   567dacf4-a6e5-461f-b094-8ded155a5a7e
--     member SPOUSE Vikram Verma 7971f862-38fe-4a72-9193-f8d5a7bc591b
--   POL-2026-00018 (lapsed)   32ee76ff-0f43-4c41-8eb3-f760d0a26a29
--     member SELF   Asha Verma   6ee0f2b2-1c47-43a7-b6f6-3af17479a96f

INSERT INTO policyholder (id, user_id, full_name, date_of_birth, phone, email) VALUES
    ('a2b56321-c8b8-4159-93d6-3cf613b3c556', '4e33233c-a255-4c75-963a-6809cd3b0cdd',
     'Asha Verma', '1989-06-12', '+91-98450-00017', 'patient1@example.com');

INSERT INTO plan (id, name, deductible, copay_pct, room_rent_limit) VALUES
    ('787465cc-3880-4abe-8d7b-463662e0ff9b', 'Family Health Plus', 5000.00, 10.00, 5000.00);

INSERT INTO coverage_rule (id, plan_id, treatment_category, covered, sub_limit, waiting_period_days) VALUES
    ('a62791d0-3280-496a-a1eb-2ccd511822d8', '787465cc-3880-4abe-8d7b-463662e0ff9b', 'HOSPITALISATION', true,  300000.00, 0),
    ('369daefc-e846-4118-a7c5-10176d3fb165', '787465cc-3880-4abe-8d7b-463662e0ff9b', 'SURGERY',         true,  200000.00, 0),
    ('b0eaf8a1-3980-4a42-b472-15f8484bd133', '787465cc-3880-4abe-8d7b-463662e0ff9b', 'MATERNITY',       true,   75000.00, 270),
    ('600dc46d-8375-4531-bfb2-ec8eb967b6d6', '787465cc-3880-4abe-8d7b-463662e0ff9b', 'DAYCARE',         true,   50000.00, 0),
    ('e66cbe8a-5646-4403-b2e6-96a6ef039afb', '787465cc-3880-4abe-8d7b-463662e0ff9b', 'COSMETIC',        false,      NULL, 0);

INSERT INTO policy (id, policy_no, policyholder_id, plan_id, start_date, end_date, sum_insured, status) VALUES
    ('f87ebb00-4f1a-4110-a200-653ccd12b9c7', 'POL-2026-00017', 'a2b56321-c8b8-4159-93d6-3cf613b3c556',
     '787465cc-3880-4abe-8d7b-463662e0ff9b', '2026-01-01', '2026-12-31', 500000.00, 'ACTIVE'),
    -- Ended in the past: for testing eligibility ("policy active on the admission date").
    ('32ee76ff-0f43-4c41-8eb3-f760d0a26a29', 'POL-2026-00018', 'a2b56321-c8b8-4159-93d6-3cf613b3c556',
     '787465cc-3880-4abe-8d7b-463662e0ff9b', '2025-01-01', '2025-12-31', 300000.00, 'LAPSED');

INSERT INTO insured_member (id, policy_id, full_name, relation, date_of_birth) VALUES
    ('567dacf4-a6e5-461f-b094-8ded155a5a7e', 'f87ebb00-4f1a-4110-a200-653ccd12b9c7', 'Asha Verma',   'SELF',   '1989-06-12'),
    ('7971f862-38fe-4a72-9193-f8d5a7bc591b', 'f87ebb00-4f1a-4110-a200-653ccd12b9c7', 'Vikram Verma', 'SPOUSE', '1987-02-03'),
    ('6ee0f2b2-1c47-43a7-b6f6-3af17479a96f', '32ee76ff-0f43-4c41-8eb3-f760d0a26a29', 'Asha Verma',   'SELF',   '1989-06-12');

INSERT INTO policy_balance (policy_id, utilised, reserved) VALUES
    ('f87ebb00-4f1a-4110-a200-653ccd12b9c7', 0, 0),
    ('32ee76ff-0f43-4c41-8eb3-f760d0a26a29', 0, 0);
