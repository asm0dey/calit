-- Single-use booking invites: a 30-day URL that books exactly one meeting type once. Like
-- password_reset_token, only the SHA-256 hash of the raw token is stored. The row is deleted when a
-- booking commits, so a consumed invite never comes back (cancelling the booking does not revive it).

CREATE TABLE booking_invite (
    id              BIGSERIAL    PRIMARY KEY,
    owner_id        BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    meeting_type_id BIGINT       NOT NULL REFERENCES meeting_type(id) ON DELETE CASCADE,
    token_hash      TEXT         NOT NULL UNIQUE,   -- lowercase hex SHA-256 of the raw token
    expires_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_booking_invite_owner ON booking_invite(owner_id);
