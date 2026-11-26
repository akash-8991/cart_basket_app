-- Phase 3.4: server-controlled feature flags and provider approval records.

-- Seeded from contracts/feature-flags.yaml at startup (missing keys only; operator changes are kept).
CREATE TABLE feature_flags (
    flag_key    TEXT PRIMARY KEY,
    enabled     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  TEXT        NOT NULL DEFAULT 'seed',
    reason      TEXT        NOT NULL DEFAULT 'default from contracts/feature-flags.yaml'
);

-- A provider can only be `enabled` with a completed approval record (provider-matrix.md, plan rule 4).
-- The record itself (reviews, evidence, sign-offs) lives where `record_url` points.
CREATE TABLE provider_approvals (
    provider_key     TEXT PRIMARY KEY,
    adapter_version  TEXT        NOT NULL,
    approved_by      TEXT        NOT NULL,
    record_url       TEXT        NOT NULL,
    approved_at      TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ
);
