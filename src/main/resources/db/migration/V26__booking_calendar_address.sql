-- calit-rma2: record WHERE a booking's Google event lives, so later update/delete address that
-- calendar instead of whatever the owner's write target is at the time of the call.
--
-- Both columns are nullable and deliberately NOT backfilled: NULL means "address unknown, resolve
-- the owner's default write target" (exactly the pre-1.21 behaviour). Stamping existing rows with
-- the current write target would be wrong for precisely the bookings this fixes.
--
-- google_calendar_id stores GOOGLE's calendar id (an email or opaque id), not google_calendar.id:
-- CalendarSelectionService.save() deletes and re-inserts every local row on each settings save, so
-- local ids churn. The credential is kept because a calendar id alone carries no OAuth token.
ALTER TABLE booking
    ADD COLUMN google_calendar_id   text,
    ADD COLUMN google_credential_id bigint REFERENCES google_credential (id) ON DELETE SET NULL;
