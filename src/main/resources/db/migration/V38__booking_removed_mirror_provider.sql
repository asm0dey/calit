-- Which provider held a booking's Mirror event when calit removed it (cancel, re-approval reschedule), so the mail
-- sent afterwards (the cancel, a later decline) knows whether that provider had invited the attendees or calit had.
-- Nullable and unread by the previous release.
ALTER TABLE booking
    ADD COLUMN removed_mirror_provider TEXT;
