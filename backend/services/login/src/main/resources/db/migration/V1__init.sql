-- Login database schema (see the "Login Database" ER diagram in the root README).

CREATE TABLE users (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    username        varchar(100) NOT NULL,
    email           varchar(255) NOT NULL,
    password_hash   varchar(100) NOT NULL,                 -- BCrypt
    role            varchar(20)  NOT NULL,
    status          varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    policyholder_id uuid,                                  -- id in the Policy DB; NULL for adjusters
    full_name       varchar(200) NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    last_login_at   timestamptz,

    CONSTRAINT uq_users_username UNIQUE (username),        -- also serves as the username index
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT ck_users_role     CHECK (role IN ('POLICYHOLDER', 'ADJUSTER')),
    CONSTRAINT ck_users_status   CHECK (status IN ('ACTIVE', 'LOCKED'))
);

CREATE TABLE refresh_tokens (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL,                       -- SHA-256 hex of the token, never the token
    expires_at timestamptz NOT NULL,
    revoked    boolean     NOT NULL DEFAULT false,

    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE login_attempts (
    id           bigserial    PRIMARY KEY,
    user_id      uuid         REFERENCES users (id) ON DELETE SET NULL,  -- NULL when the username is unknown
    username     varchar(100) NOT NULL,
    success      boolean      NOT NULL,
    ip_address   varchar(45),
    attempted_at timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_attempts_user_time     ON login_attempts (user_id, attempted_at);
CREATE INDEX idx_login_attempts_username_time ON login_attempts (username, attempted_at);
