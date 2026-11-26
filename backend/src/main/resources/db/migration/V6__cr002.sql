-- CR-002: device model for the sessions list (B) and stable activity summary keys (E).
ALTER TABLE sessions ADD COLUMN device_model TEXT;

ALTER TABLE activity_events
    ADD COLUMN summary_key  TEXT CHECK (summary_key ~ '^[a-z_.]+$'),
    -- Template values for the app's own copy; never personal data (DataRequestsTest/MeTest check).
    ADD COLUMN summary_args JSONB;
