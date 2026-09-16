-- GDPR epic. Four independent changes, no backfill: existing rows are not erased and
-- retention stays off until an operator opts in, so an upgrade changes nothing by itself.

-- 1. Invitee erasure marker. NULL = not erased. The booking ROW survives erasure so the owner
--    keeps a "someone booked 14:00-14:30" record; this stamp is what makes the invitee-facing
--    routes 404 and what the owner's list renders as a placeholder instead of a blank name.
ALTER TABLE booking ADD COLUMN erased_at TIMESTAMPTZ;

-- 2. Per-owner retention override. NULL = fall back to the instance default
--    (calit.retention.booking-days), which is itself unset by default = keep forever.
ALTER TABLE owner_settings ADD COLUMN booking_retention_days INT;

-- 3. email_outbox was orphan personal data: it holds the rendered HTML of every booking mail
--    with the recipient address, and nothing cascaded to it, so it survived account deletion.
--    Matching on recipient alone is NOT a fix -- two owners can mail the same invitee, and
--    erasing one booking would take the other owner's mail with it. Link the rows instead.
--    Both nullable: rows enqueued before V34 have no link and are cleared by the age purge alone.
ALTER TABLE email_outbox ADD COLUMN booking_id BIGINT REFERENCES booking(id)  ON DELETE CASCADE;
ALTER TABLE email_outbox ADD COLUMN owner_id   BIGINT REFERENCES app_user(id) ON DELETE CASCADE;
CREATE INDEX idx_email_outbox_booking ON email_outbox (booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX idx_email_outbox_owner   ON email_outbox (owner_id)   WHERE owner_id IS NOT NULL;

-- 4. V4 declared booking.meeting_type_id REFERENCES meeting_type(id) with no ON DELETE clause.
--    Owner deletion resolves today only because meeting_type and booking each cascade separately
--    from app_user. Make the intent explicit rather than leave it as an accident of ordering.
ALTER TABLE booking DROP CONSTRAINT booking_meeting_type_id_fkey;
ALTER TABLE booking ADD CONSTRAINT booking_meeting_type_id_fkey
    FOREIGN KEY (meeting_type_id) REFERENCES meeting_type(id) ON DELETE CASCADE;
