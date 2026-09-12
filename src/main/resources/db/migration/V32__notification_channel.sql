-- Feature #194: per-owner outbound notification channels, delivered via notify4j.
-- url is secret-bearing (bot tokens, webhook secrets) and is encrypted at rest by
-- EncryptedStringConverter, so it is TEXT and carries NO unique constraint: AES-GCM uses a random
-- IV, so the same URL encrypts differently every time and ciphertext comparison is meaningless.
CREATE TABLE notification_channel (
    id              BIGSERIAL   PRIMARY KEY,
    owner_id        BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    url             TEXT        NOT NULL,          -- secret-bearing, encrypted at rest
    label           VARCHAR(64),                   -- owner's own name; NOT unique
    created_at      TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ
);
CREATE INDEX idx_notification_channel_owner ON notification_channel (owner_id);

-- Per-meeting-type OVERRIDE, modelled on meeting_type_host (V20).
-- No rows for a (host, type) pair = that host inherits ALL of their own channels.
CREATE TABLE notification_channel_meeting_type (
    id              BIGSERIAL PRIMARY KEY,
    channel_id      BIGINT NOT NULL REFERENCES notification_channel(id) ON DELETE CASCADE,
    meeting_type_id BIGINT NOT NULL REFERENCES meeting_type(id)         ON DELETE CASCADE,
    CONSTRAINT uq_ncmt UNIQUE (channel_id, meeting_type_id)
);
CREATE INDEX idx_ncmt_channel ON notification_channel_meeting_type (channel_id);
CREATE INDEX idx_ncmt_type    ON notification_channel_meeting_type (meeting_type_id);
