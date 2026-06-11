-- Phase 1 (multi-user auth foundation): DB-backed users replace the embedded
-- properties admin. One row per user. Phase 2+ adds owner_id FKs to tenant tables;
-- this migration only introduces the identity store.
CREATE TABLE app_user (
    id                   BIGSERIAL    PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL UNIQUE,   -- lowercased, URL-safe, validated in app
    password_hash        TEXT         NOT NULL,          -- argon2id, $argon2id$... MCF string
    roles                VARCHAR(64)  NOT NULL,          -- 'user' or 'user,admin'
    is_admin             BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    settings_complete    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL
);
