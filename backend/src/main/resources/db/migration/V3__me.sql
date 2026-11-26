-- Phase 3.3: profile settings, consents (current state + append-only history), activity feed,
-- data export / deletion requests.

ALTER TABLE users
    ADD COLUMN privacy_mode TEXT NOT NULL DEFAULT 'device_only' CHECK (privacy_mode IN ('device_only', 'normalized_upload')),
    ADD COLUMN deletion_requested_at TIMESTAMPTZ;

-- Current decision per consent key.
CREATE TABLE consents (
    user_id      TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    consent_key  TEXT        NOT NULL,
    granted      BOOLEAN     NOT NULL,
    version      TEXT        NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, consent_key)
);

-- Every change, never edited: the record of what the user agreed to, which version, and when.
-- Rows go only when the account itself is erased.
CREATE TABLE consent_events (
    id             BIGSERIAL PRIMARY KEY,
    user_id        TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    consent_key    TEXT        NOT NULL,
    granted        BOOLEAN     NOT NULL,
    version        TEXT        NOT NULL,
    at             TIMESTAMPTZ NOT NULL,
    correlation_id TEXT
);
CREATE INDEX consent_events_user ON consent_events (user_id, at);
CREATE FUNCTION consent_events_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'consent_events rows cannot be changed';
END;
$$;
CREATE TRIGGER consent_events_no_update BEFORE UPDATE ON consent_events
    FOR EACH ROW EXECUTE FUNCTION consent_events_immutable();

-- User-facing activity feed (settings → activity).
CREATE TABLE activity_events (
    event_id   TEXT PRIMARY KEY,
    user_id    TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    at         TIMESTAMPTZ NOT NULL,
    type       TEXT        NOT NULL CHECK (type IN ('automation_approved', 'automation_stopped', 'automation_completed', 'automation_failed',
                                                   'consent_granted', 'consent_revoked', 'history_imported', 'data_exported',
                                                   'data_deleted', 'session_revoked')),
    summary    TEXT        NOT NULL,
    provider   TEXT,
    task_id    TEXT
);
CREATE INDEX activity_events_page ON activity_events (user_id, at DESC, event_id DESC);

-- Export and deletion requests, carried out by DataRequestProcessor.
CREATE TABLE data_requests (
    request_id    TEXT PRIMARY KEY,
    user_id       TEXT        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    kind          TEXT        NOT NULL CHECK (kind IN ('export', 'delete')),
    scope         TEXT        NOT NULL CHECK (scope IN ('all', 'history', 'chat', 'account')),
    status        TEXT        NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'in_progress', 'completed', 'failed')),
    created_at    TIMESTAMPTZ NOT NULL,
    completed_at  TIMESTAMPTZ
);
CREATE INDEX data_requests_pending ON data_requests (created_at) WHERE status IN ('queued', 'in_progress');
