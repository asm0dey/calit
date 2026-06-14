-- Forgot-password: single-use, expiring reset tokens. Mirrors login_ticket — only the
-- SHA-256 hash of the raw token is stored; the raw token goes out in the reset email link
-- and is consumed (deleted) when the new password is set.

CREATE TABLE password_reset_token (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  TEXT         NOT NULL UNIQUE,   -- lowercase hex SHA-256 of the raw token
    expires_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_password_reset_token_user ON password_reset_token(user_id);
