-- Phase 3.1 baseline. Feature tables arrive with their slices (3.2 auth, 3.3 me & consent, …).

-- Append-only record of security-relevant actions (logins, approvals, consent changes, admin flag flips).
-- No secrets or raw personal data: IPs are stored as keyed hashes, details hold IDs and codes only.
CREATE TABLE audit_log (
    id             BIGSERIAL PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id  TEXT,
    action         TEXT        NOT NULL,
    target         TEXT,
    outcome        TEXT        NOT NULL,
    correlation_id TEXT,
    ip_hash        TEXT,
    detail         JSONB       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_log_actor_time ON audit_log (actor_user_id, occurred_at DESC);

-- The application role may insert and read audit rows but never change or delete them.
CREATE FUNCTION audit_log_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$;
CREATE TRIGGER audit_log_no_update BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
