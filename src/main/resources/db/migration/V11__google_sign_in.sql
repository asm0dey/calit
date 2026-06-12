-- Sign in with Google: OAuth-only users have no password; link a Google account by its
-- stable id_token "sub". A single-use login ticket bridges a verified Google identity into
-- a native Quarkus form-auth session (the framework exposes no API to mint that cookie directly).

ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE app_user ADD COLUMN google_sub VARCHAR(255);
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_google_sub UNIQUE (google_sub);

CREATE TABLE login_ticket (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,   -- lowercase hex SHA-256 of the raw token
    expires_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_login_ticket_user ON login_ticket(user_id);
