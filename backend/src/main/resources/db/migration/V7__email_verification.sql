-- CR-003 §2: email verification codes. One live code per user; hashed with the server key, single use,
-- 15-minute lifetime, locked after 5 wrong attempts (same design as password-reset codes).
CREATE TABLE email_verifications (
    user_id     TEXT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    code_hash   BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0
);
