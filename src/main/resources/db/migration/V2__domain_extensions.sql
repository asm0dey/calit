-- Feature 11: per-type min scheduling notice + booking horizon (columns only; filtered in Plan 3).
-- Feature 13: per-type meeting location (Meet/phone/in-person/custom).
-- Feature 14a: per-type approval workflow flag.
ALTER TABLE meeting_type ADD COLUMN min_notice_minutes INT         NOT NULL DEFAULT 0;
ALTER TABLE meeting_type ADD COLUMN horizon_days       INT         NOT NULL DEFAULT 60;
ALTER TABLE meeting_type ADD COLUMN location_type      VARCHAR(16) NOT NULL DEFAULT 'GOOGLE_MEET';
ALTER TABLE meeting_type ADD COLUMN location_detail    TEXT;
ALTER TABLE meeting_type ADD COLUMN requires_approval  BOOLEAN     NOT NULL DEFAULT FALSE;

-- Owner-notify opt-out.
ALTER TABLE owner_settings ADD COLUMN owner_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- Feature 12: date-specific availability overrides (replace semantics, per-type -> global, empty = day off).
CREATE TABLE date_override (
    id              BIGSERIAL PRIMARY KEY,
    meeting_type_id BIGINT    REFERENCES meeting_type(id) ON DELETE CASCADE,  -- null = global
    override_date   DATE      NOT NULL
);

-- One override per (scope, date). COALESCE folds the global (null) scope to 0 so it is unique too.
CREATE UNIQUE INDEX uq_date_override_scope_date ON date_override (COALESCE(meeting_type_id, 0), override_date);

CREATE TABLE date_override_window (
    id               BIGSERIAL PRIMARY KEY,
    date_override_id BIGINT    NOT NULL REFERENCES date_override(id) ON DELETE CASCADE,
    start_time       TIME      NOT NULL,
    end_time         TIME      NOT NULL
);

CREATE INDEX idx_date_override_window_parent ON date_override_window (date_override_id);
