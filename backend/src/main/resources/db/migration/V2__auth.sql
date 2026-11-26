-- Phase 3.2: accounts, device sessions, rotating refresh tokens, password/user-ID recovery.

CREATE TABLE users (
    user_id           TEXT PRIMARY KEY CHECK (user_id ~ '^[a-z0-9._-]{4,30}$'),
    email             TEXT        NOT NULL,
    email_verified    BOOLEAN     NOT NULL DEFAULT false,
    password_hash     TEXT        NOT NULL,          -- Argon2id PHC string (D-018)
    terms_version     TEXT        NOT NULL,
    privacy_version   TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Emails are compared case-insensitively; several user IDs may share one email (forgot-user-id sends all).
CREATE INDEX users_email ON users (lower(email));

-- One row per signed-in device. The session is also the refresh-token family (D-018).
CREATE TABLE sessions (
    session_id     TEXT PRIMARY KEY,
    user_id        TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_id      TEXT        NOT NULL,
    platform       TEXT        NOT NULL,
    app_version    TEXT        NOT NULL,
    os_version     TEXT,
    remembered     BOOLEAN     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    revoke_reason  TEXT
);
CREATE INDEX sessions_user_active ON sessions (user_id) WHERE revoked_at IS NULL;

-- Only SHA-256 of each refresh token is stored. A token is usable once; presenting a used token
-- revokes its whole session (reuse detection).
CREATE TABLE refresh_tokens (
    token_hash   BYTEA PRIMARY KEY,
    session_id   TEXT        NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at      TIMESTAMPTZ
);
CREATE INDEX refresh_tokens_session ON refresh_tokens (session_id);

-- Password-reset challenges. Created only for a matching user ID + email, but the API answers every
-- request identically. The code is stored hashed; a challenge dies after expiry, success or 5 wrong codes.
CREATE TABLE recovery_challenges (
    challenge_id  TEXT PRIMARY KEY,
    user_id       TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    code_hash     BYTEA       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    attempts      INT         NOT NULL DEFAULT 0,
    consumed_at   TIMESTAMPTZ
);

-- Development/test mail sink until a mail provider is chosen (no provider in Phase 3).
-- Holds recovery codes, so the production configuration must not use it.
CREATE TABLE email_outbox (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    recipient   TEXT        NOT NULL,
    template    TEXT        NOT NULL,
    params      JSONB       NOT NULL
);
