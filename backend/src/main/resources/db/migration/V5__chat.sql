-- Phase 3.5: category-scoped chat sessions and messages.
-- Chat text is sensitive personal data (data-classification.md): it stays in these tables, is never
-- logged, and goes when the user deletes the session, their chat history, or their account.

CREATE TABLE chat_sessions (
    session_id  TEXT PRIMARY KEY,
    user_id     TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    category    TEXT        NOT NULL CHECK (category IN ('travel', 'food', 'grocery', 'shopping')),
    title       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ,
    -- Orchestrator state between turns, e.g. the pending clarification and the draft it belongs to.
    state       JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX chat_sessions_page ON chat_sessions (user_id, created_at DESC, session_id DESC);

CREATE TABLE chat_messages (
    message_id  TEXT PRIMARY KEY,
    session_id  TEXT        NOT NULL REFERENCES chat_sessions(session_id) ON DELETE CASCADE,
    seq         BIGSERIAL   NOT NULL,
    role        TEXT        NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    text        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    turn        JSONB
);
CREATE INDEX chat_messages_session ON chat_messages (session_id, seq);
