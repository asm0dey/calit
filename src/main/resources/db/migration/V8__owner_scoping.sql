-- Phase 2 owner scoping: every root tenant table gains owner_id -> app_user(id).
-- Fresh start, no backfill (dev DB reset). Singleton/global-unique assumptions are dropped;
-- uniqueness becomes per-owner.

-- owner_settings: was a singleton (id = 1). Now one row per owner, owner_id UNIQUE.
ALTER TABLE owner_settings ADD COLUMN owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE owner_settings ADD CONSTRAINT uq_owner_settings_owner UNIQUE (owner_id);

-- meeting_type: slug moves from globally UNIQUE to UNIQUE (owner_id, slug).
ALTER TABLE meeting_type ADD COLUMN owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE meeting_type DROP CONSTRAINT meeting_type_slug_key;
ALTER TABLE meeting_type ADD CONSTRAINT uq_meeting_type_owner_slug UNIQUE (owner_id, slug);
CREATE INDEX idx_meeting_type_owner ON meeting_type (owner_id);

-- booking: owner_id denormalized from its meeting type so dashboard/pending filter without a join.
ALTER TABLE booking ADD COLUMN owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
CREATE INDEX idx_booking_owner_status ON booking (owner_id, status);

-- booking_field: drop the V1 global default seed (meeting_type_id IS NULL); global defaults are now
-- per-owner. owner_id + index for owner-scoped global-form lookups.
ALTER TABLE booking_field ADD COLUMN owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
DELETE FROM booking_field WHERE meeting_type_id IS NULL;
CREATE INDEX idx_booking_field_owner_scope ON booking_field (owner_id, meeting_type_id, position);

-- availability_rule: global rows (meeting_type_id IS NULL = "applies to all of this owner's types")
-- have no parent FK to inherit ownership from, so they carry their own denormalized owner_id (set on
-- EVERY rule at creation, not only globals, so queries filter uniformly). Index for the global lookup.
ALTER TABLE availability_rule ADD COLUMN owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
CREATE INDEX idx_availability_rule_owner_scope ON availability_rule (owner_id, meeting_type_id, day_of_week);

-- date_override: same as availability_rule — global (meeting_type_id IS NULL) overrides need their own
-- owner_id. date_override_window stays parent-scoped via date_override (NO owner_id column).
ALTER TABLE date_override ADD COLUMN owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
CREATE INDEX idx_date_override_owner_scope ON date_override (owner_id, meeting_type_id, override_date);

-- google_credential: was a singleton (id = 1). Now one row per owner, owner_id UNIQUE.
ALTER TABLE google_credential ADD COLUMN owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE google_credential ADD CONSTRAINT uq_google_credential_owner UNIQUE (owner_id);

-- google_calendar: google_calendar_id moves from globally UNIQUE to UNIQUE (owner_id, google_calendar_id)
-- (two owners may sync the same shared calendar). The single-write-target index becomes per-owner.
ALTER TABLE google_calendar ADD COLUMN owner_id BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE google_calendar DROP CONSTRAINT google_calendar_google_calendar_id_key;
ALTER TABLE google_calendar ADD CONSTRAINT uq_google_calendar_owner_cal
    UNIQUE (owner_id, google_calendar_id);
DROP INDEX idx_google_calendar_single_write_target;
CREATE UNIQUE INDEX idx_google_calendar_single_write_target
    ON google_calendar (owner_id)
    WHERE write_target = TRUE;
