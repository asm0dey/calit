-- Multi-account Google support. Existing single-account rows cannot be backfilled with a real
-- account identity (google_sub only comes from an id_token, which needs the new openid scope, i.e. a
-- re-consent). Re-consent is mandatory anyway, so wipe and let a clean reconnect rebuild everything.
DELETE FROM google_calendar;
DELETE FROM google_credential;

ALTER TABLE google_credential DROP CONSTRAINT uq_google_credential_owner;
ALTER TABLE google_credential ADD COLUMN google_sub      VARCHAR(255) NOT NULL;
ALTER TABLE google_credential ADD COLUMN account_email   VARCHAR(255);
ALTER TABLE google_credential ADD COLUMN needs_reconnect BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE google_credential ADD CONSTRAINT uq_google_credential_owner_sub UNIQUE (owner_id, google_sub);

ALTER TABLE google_calendar ADD COLUMN google_credential_id BIGINT NOT NULL
    REFERENCES google_credential(id) ON DELETE CASCADE;
ALTER TABLE google_calendar DROP CONSTRAINT uq_google_calendar_owner_cal;
ALTER TABLE google_calendar ADD CONSTRAINT uq_google_calendar_cred_cal
    UNIQUE (google_credential_id, google_calendar_id);
-- Keep idx_google_calendar_single_write_target ON (owner_id) WHERE write_target = TRUE (one write target per owner).
