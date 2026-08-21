-- calit-bh5t: an OPTIONAL per-(meeting type, host) write calendar. meeting_type holds the creator's
-- choice; meeting_type_host holds each co-host's own choice for that shared type. Both NULL (the
-- default, and what every existing row gets -- no backfill) means "use that owner's default write
-- target", i.e. exactly today's behaviour.
--
-- Stored as a (credential, Google calendar id) pair rather than an FK to google_calendar.id:
-- CalendarSelectionService.save() deletes and re-inserts every local row on each settings save, so
-- local ids churn whenever the owner touches a checkbox, while the Google-side id survives. The
-- credential is kept because a calendar id alone carries no OAuth token, and because a calendar
-- shared into two connected accounts yields two rows with the same google_calendar_id.
--
-- ON DELETE SET NULL: disconnecting the account degrades the override to "unset" (fall back to the
-- default write target) instead of blocking the delete.
ALTER TABLE meeting_type
    ADD COLUMN google_calendar_id   text,
    ADD COLUMN google_credential_id bigint REFERENCES google_credential (id) ON DELETE SET NULL;

ALTER TABLE meeting_type_host
    ADD COLUMN google_calendar_id   text,
    ADD COLUMN google_credential_id bigint REFERENCES google_credential (id) ON DELETE SET NULL;
